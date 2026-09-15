#include "protocol.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iomanip>
#include <iostream>
#include <limits>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
using SocketHandle = SOCKET;
constexpr SocketHandle INVALID_SOCKET_HANDLE = INVALID_SOCKET;
using SocketLength = int;
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>
using SocketHandle = int;
constexpr SocketHandle INVALID_SOCKET_HANDLE = -1;
using SocketLength = socklen_t;
#endif

namespace {

using namespace sc6103;
using Clock = std::chrono::steady_clock;

constexpr std::size_t MAX_MONITORS = 64;
constexpr std::size_t MAX_HISTORY = 512;
constexpr std::size_t MAX_HISTORY_PER_CLIENT = 64;
constexpr std::size_t MAX_ORACLE_EVENTS = 512;
constexpr auto HISTORY_TTL = std::chrono::seconds(60);
constexpr auto MAX_POLL = std::chrono::milliseconds(100);
constexpr auto CLIENT_RETRY_WINDOW = std::chrono::milliseconds(750 * 4);

static_assert(HISTORY_TTL > CLIENT_RETRY_WINDOW,
              "reply history must outlive the documented client retry window");

std::atomic<bool> keep_running{true};

void stop_server(int) {
    keep_running.store(false);
}

void close_socket(SocketHandle socket) {
#ifdef _WIN32
    closesocket(socket);
#else
    close(socket);
#endif
}

std::string socket_error() {
#ifdef _WIN32
    return std::to_string(WSAGetLastError());
#else
    return std::strerror(errno);
#endif
}

std::string timestamp() {
    const auto now = std::chrono::system_clock::now();
    const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()) % 1000;
    const std::time_t value = std::chrono::system_clock::to_time_t(now);
    std::tm local{};
#ifdef _WIN32
    localtime_s(&local, &value);
#else
    localtime_r(&value, &local);
#endif
    std::ostringstream out;
    out << std::put_time(&local, "%H:%M:%S") << '.'
        << std::setfill('0') << std::setw(3) << millis.count();
    return out.str();
}

// clientId is printed as 0x%08X because that is how the Java client prints it in its
// banner and on every trace line. README sec. 10.2 fixes the field order so logs from
// three machines can be read side by side, which fails if one side prints the same
// uint32 in decimal.
std::string client_text(std::uint32_t client_id) {
    std::ostringstream out;
    out << "0x" << std::uppercase << std::hex << std::setfill('0') << std::setw(8)
        << client_id;
    return out.str();
}

void log_line(const char* event, const char* direction, std::uint32_t client_id,
              std::int32_t request_id, std::int32_t opcode,
              const std::string& decision, const std::string& state = "-") {
    std::cout << timestamp() << " SERVER " << std::left << std::setw(10) << event
              << ' ' << std::setw(9) << direction
              << " client=" << client_text(client_id)
              << " req=" << request_id
              << " opcode=" << opcode_name(opcode)
              << " attempt=- decision=" << decision
              << " state=" << state << std::endl;
}

struct Config {
    std::uint16_t port = 0;
    std::int32_t semantics = SEM_AT_MOST_ONCE;
    double drop_request = 0.0;
    double drop_reply = 0.0;
    double drop_callback = 0.0;
    std::int32_t drop_first_reply_opcode = OP_NONE;
    bool drop_first_callback = false;
    std::uint64_t seed = static_cast<std::uint64_t>(
        std::chrono::high_resolution_clock::now().time_since_epoch().count());
};

const char* usage_text() {
    return
        "Usage:\n"
        "  flight_server --port <port>\n"
        "                [--semantics at-least-once|at-most-once]\n"
        "                [--drop-request <p>] [--drop-reply <p>]\n"
        "                [--drop-callback <p>]\n"
        "                [--drop-first-reply <1-6>] [--drop-first-callback]\n"
        "                [--seed <n>] [--help]\n";
}

const char* require_value(int argc, char** argv, int index) {
    if (index + 1 >= argc) {
        throw std::invalid_argument(std::string(argv[index]) + " requires a value");
    }
    return argv[index + 1];
}

long parse_long(const char* flag, const char* text, long minimum, long maximum) {
    char* end = nullptr;
    errno = 0;
    const long value = std::strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value < minimum || value > maximum) {
        throw std::invalid_argument(std::string(flag) + " has invalid value " + text);
    }
    return value;
}

double parse_probability(const char* flag, const char* text) {
    char* end = nullptr;
    errno = 0;
    const double value = std::strtod(text, &end);
    if (errno != 0 || end == text || *end != '\0' || !std::isfinite(value)
            || value < 0.0 || value > 1.0) {
        throw std::invalid_argument(std::string(flag) + " must be in [0,1]");
    }
    return value;
}

Config parse_config(int argc, char** argv) {
    Config config;
    for (int i = 1; i < argc;) {
        const std::string flag(argv[i]);
        if (flag == "--help") {
            std::cout << usage_text();
            std::exit(0);
        } else if (flag == "--drop-first-callback") {
            config.drop_first_callback = true;
            ++i;
        } else if (flag == "--port") {
            config.port = static_cast<std::uint16_t>(
                parse_long("--port", require_value(argc, argv, i), 1, 65535));
            i += 2;
        } else if (flag == "--semantics") {
            const std::string value(require_value(argc, argv, i));
            if (value == "at-least-once") config.semantics = SEM_AT_LEAST_ONCE;
            else if (value == "at-most-once") config.semantics = SEM_AT_MOST_ONCE;
            else throw std::invalid_argument("--semantics must be at-least-once or at-most-once");
            i += 2;
        } else if (flag == "--drop-request") {
            config.drop_request = parse_probability("--drop-request", require_value(argc, argv, i));
            i += 2;
        } else if (flag == "--drop-reply") {
            config.drop_reply = parse_probability("--drop-reply", require_value(argc, argv, i));
            i += 2;
        } else if (flag == "--drop-callback") {
            config.drop_callback = parse_probability("--drop-callback", require_value(argc, argv, i));
            i += 2;
        } else if (flag == "--drop-first-reply") {
            config.drop_first_reply_opcode = static_cast<std::int32_t>(
                parse_long("--drop-first-reply", require_value(argc, argv, i), 1, 6));
            i += 2;
        } else if (flag == "--seed") {
            char* end = nullptr;
            errno = 0;
            const unsigned long long value = std::strtoull(require_value(argc, argv, i), &end, 10);
            if (errno != 0 || end == argv[i + 1] || *end != '\0') {
                throw std::invalid_argument("--seed must be an unsigned integer");
            }
            config.seed = static_cast<std::uint64_t>(value);
            i += 2;
        } else {
            throw std::invalid_argument("unknown argument " + flag);
        }
    }
    if (config.port == 0) throw std::invalid_argument("--port is required");
    return config;
}

class LossSimulator {
public:
    explicit LossSimulator(const Config& config)
        : rng_(config.seed), distribution_(0.0, 1.0), request_rate_(config.drop_request),
          reply_rate_(config.drop_reply), callback_rate_(config.drop_callback),
          first_reply_opcode_(config.drop_first_reply_opcode),
          first_callback_(config.drop_first_callback) {}

    bool drop_request() {
        reason_ = "injected-probability";
        return trial(request_rate_);
    }

    bool drop_reply(std::int32_t opcode) {
        if (first_reply_opcode_ == opcode) {
            first_reply_opcode_ = OP_NONE;
            reason_ = "forced-first-reply";
            return true;
        }
        reason_ = "injected-probability";
        return trial(reply_rate_);
    }

    bool drop_callback() {
        if (first_callback_) {
            first_callback_ = false;
            reason_ = "forced-first-callback";
            return true;
        }
        reason_ = "injected-probability";
        return trial(callback_rate_);
    }

    const std::string& reason() const { return reason_; }

private:
    bool trial(double rate) {
        return rate > 0.0 && distribution_(rng_) < rate;
    }

    std::mt19937_64 rng_;
    std::uniform_real_distribution<double> distribution_;
    double request_rate_;
    double reply_rate_;
    double callback_rate_;
    std::int32_t first_reply_opcode_;
    bool first_callback_;
    std::string reason_;
};

struct DepartureTime {
    std::int32_t year;
    std::int32_t month;
    std::int32_t day;
    std::int32_t hour;
    std::int32_t minute;
};

struct Flight {
    std::int32_t id;
    const char* source;
    const char* destination;
    DepartureTime departure;
    float fare;
    std::int32_t total_seats;
    std::int32_t seats;
    float expected_fare;
    std::int32_t expected_seats;
};

std::array<Flight, 6> seed_flights() {
    return {{
        {1934, "Singapore", "London", {2026, 10, 14, 9, 30}, 899.50F, 120, 120, 899.50F, 120},
        {2201, "Singapore", "London", {2026, 10, 15, 23, 5}, 1204.00F, 6, 6, 1204.00F, 6},
        {88, "Singapore", "London", {2026, 10, 16, 7, 15}, 742.25F, 0, 0, 742.25F, 0},
        {412, "Singapore", "Tokyo", {2026, 10, 17, 12, 0}, 320.25F, 2, 2, 320.25F, 2},
        {7300, "Oslo", "Reykjavik", {2026, 10, 18, 8, 45}, 1.00F, 18, 18, 1.00F, 18},
        {9500, "Sydney", "Auckland", {2026, 10, 19, 16, 20}, 99999.50F, 40, 40, 99999.50F, 40}
    }};
}

struct Endpoint {
    sockaddr_storage address{};
    SocketLength length = 0;
};

std::string endpoint_text(const Endpoint& endpoint) {
    char host[NI_MAXHOST]{};
    char service[NI_MAXSERV]{};
    const int result = getnameinfo(reinterpret_cast<const sockaddr*>(&endpoint.address),
                                   endpoint.length, host, sizeof(host), service,
                                   sizeof(service), NI_NUMERICHOST | NI_NUMERICSERV);
    if (result != 0) return "unknown";
    return std::string(host) + ':' + service;
}

struct Monitor {
    bool used = false;
    std::int32_t flight_id = 0;
    std::uint32_t client_id = 0;
    Endpoint endpoint{};
    Clock::time_point expires_at{};
    std::int32_t request_id = 0;
    std::int32_t semantics = SEM_AT_MOST_ONCE;
    std::int32_t callback_seq = 0;
};

struct HistoryEntry {
    bool used = false;
    std::uint32_t client_id = 0;
    std::int32_t request_id = 0;
    std::uint64_t fingerprint = 0;
    std::array<std::uint8_t, MAX_DATAGRAM> reply{};
    std::size_t length = 0;
    Clock::time_point stored_at{};
    std::uint64_t order = 0;
};

struct OracleEvent {
    bool used = false;
    std::uint32_t client_id = 0;
    std::int32_t request_id = 0;
    std::uint64_t order = 0;
};

std::uint64_t fingerprint(std::int32_t opcode, const std::uint8_t* body,
                          std::size_t body_length) {
    // FNV-1a is server-private. Only stability matters; it never crosses the wire.
    std::uint64_t hash = 1469598103934665603ULL;
    for (int shift = 24; shift >= 0; shift -= 8) {
        hash ^= static_cast<std::uint8_t>((static_cast<std::uint32_t>(opcode) >> shift) & 0xFFU);
        hash *= 1099511628211ULL;
    }
    for (std::size_t i = 0; i < body_length; ++i) {
        hash ^= body[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

char ascii_fold(char value) {
    return value >= 'A' && value <= 'Z' ? static_cast<char>(value + ('a' - 'A')) : value;
}

bool ascii_space(char value) {
    return value == ' ' || value == '\t' || value == '\r' || value == '\n'
        || value == '\f' || value == '\v';
}

bool normalized_equal(const std::array<char, MAX_STRING_BYTES + 1>& input,
                      std::size_t length, const char* seeded) {
    std::size_t begin = 0;
    std::size_t end = length;
    while (begin < end && ascii_space(input[begin])) ++begin;
    while (end > begin && ascii_space(input[end - 1])) --end;
    const std::size_t seeded_length = std::strlen(seeded);
    if (end - begin != seeded_length) return false;
    for (std::size_t i = 0; i < seeded_length; ++i) {
        if (ascii_fold(input[begin + i]) != ascii_fold(seeded[i])) return false;
    }
    return true;
}

float float_from_bits(std::uint32_t bits) {
    float value = 0.0F;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

// Two decimals is exact for every fare this system produces: the seed fares and the
// README sec. 13 trial deltas are all halves and quarters, so a one-delta divergence
// is still visible in the log. The oracle itself compares bit patterns, not this text.
std::string fare_text(float value) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(2) << value;
    return out.str();
}

std::string departure_text(const DepartureTime& time) {
    std::ostringstream out;
    out << std::setfill('0') << std::setw(4) << time.year << '-'
        << std::setw(2) << time.month << '-' << std::setw(2) << time.day << ' '
        << std::setw(2) << time.hour << ':' << std::setw(2) << time.minute;
    return out.str();
}

// Place names are client-supplied bytes. Control characters and quotes are masked so
// one request always stays one log line (README sec. 10.2).
std::string quoted_place(const std::array<char, MAX_STRING_BYTES + 1>& text,
                         std::size_t length) {
    std::string result = "\"";
    for (std::size_t i = 0; i < length; ++i) {
        const auto byte = static_cast<unsigned char>(text[i]);
        result += (byte < 0x20U || byte == 0x7FU || byte == '"') ? '?' : text[i];
    }
    return result + '"';
}

class Server {
public:
    Server(SocketHandle socket, const Config& config)
        : socket_(socket), config_(config), loss_(config), flights_(seed_flights()) {}

    void run() {
        while (keep_running.load()) {
            const auto timeout = next_wait();
            fd_set read_set;
            FD_ZERO(&read_set);
            FD_SET(socket_, &read_set);
            timeval wait{};
            wait.tv_sec = static_cast<long>(timeout.count() / 1000);
            wait.tv_usec = static_cast<long>((timeout.count() % 1000) * 1000);
#ifdef _WIN32
            const int ready = select(0, &read_set, nullptr, nullptr, &wait);
#else
            const int ready = select(socket_ + 1, &read_set, nullptr, nullptr, &wait);
#endif
            if (ready > 0 && FD_ISSET(socket_, &read_set)) receive_one();
            else if (ready < 0 && keep_running.load()) {
#ifdef _WIN32
                if (WSAGetLastError() != WSAEINTR)
#else
                if (errno != EINTR)
#endif
                    std::cerr << "select failed: " << socket_error() << std::endl;
            }
            expire_monitors();
            evict_expired_history();
        }
    }

private:
    std::chrono::milliseconds next_wait() const {
        auto result = MAX_POLL;
        const auto now = Clock::now();
        for (const Monitor& monitor : monitors_) {
            if (!monitor.used) continue;
            if (monitor.expires_at <= now) return std::chrono::milliseconds(0);
            const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                monitor.expires_at - now + std::chrono::milliseconds(1));
            if (remaining < result) result = remaining;
        }
        return result;
    }

    void receive_one() {
        // One extra byte makes an oversized UDP datagram distinguishable from a
        // legitimate 512-byte one on platforms that truncate to the supplied buffer.
        std::array<std::uint8_t, MAX_DATAGRAM + 1> bytes{};
        Endpoint from;
        from.length = static_cast<SocketLength>(sizeof(from.address));
#ifdef _WIN32
        const int received = recvfrom(socket_, reinterpret_cast<char*>(bytes.data()),
                                      static_cast<int>(bytes.size()), 0,
                                      reinterpret_cast<sockaddr*>(&from.address), &from.length);
        if (received == SOCKET_ERROR) return;
#else
        const ssize_t raw_received = recvfrom(socket_, bytes.data(), bytes.size(), 0,
                                              reinterpret_cast<sockaddr*>(&from.address),
                                              &from.length);
        if (raw_received < 0) return;
        const int received = static_cast<int>(raw_received);
#endif
        Header hinted;
        if (received >= static_cast<int>(HEADER_SIZE)) {
            Reader hint_reader(bytes.data(), static_cast<std::size_t>(received));
            read_header(hint_reader, hinted);
        }
        log_line("RECV", "request", hinted.client_id, hinted.request_id, hinted.opcode,
                 "datagram", "bytes=" + std::to_string(received));

        if (loss_.drop_request()) {
            log_line("DROP", "request", hinted.client_id, hinted.request_id, hinted.opcode,
                     loss_.reason());
            return;
        }
        process(bytes.data(), static_cast<std::size_t>(received), from);
    }

    void process(const std::uint8_t* bytes, std::size_t length, const Endpoint& from) {
        if (length < HEADER_SIZE) {
            log_line("VALIDATION", "request", 0, 0, OP_NONE, "runt-datagram");
            return;
        }
        Reader reader(bytes, length);
        Header request;
        if (!read_header(reader, request)) return;
        if (request.magic != MAGIC) {
            log_line("VALIDATION", "request", request.client_id, request.request_id,
                     request.opcode, "bad-magic-drop");
            return;
        }
        if (request.version != VERSION) {
            send_uncached_error(request, ERR_VERSION_MISMATCH, from);
            return;
        }
        if (!valid_message_type(request.message_type)) {
            send_uncached_error(request, ERR_MALFORMED_REQUEST, from);
            return;
        }
        if (request.message_type != MSG_REQUEST) {
            log_line("VALIDATION", "request", request.client_id, request.request_id,
                     request.opcode, "non-request-drop");
            return;
        }
        if (!valid_semantics(request.semantics)) {
            send_uncached_error(request, ERR_MALFORMED_REQUEST, from);
            return;
        }
        if (request.payload_len < 0 || request.payload_len > static_cast<std::int32_t>(MAX_BODY)
                || length != HEADER_SIZE + static_cast<std::size_t>(request.payload_len)) {
            send_uncached_error(request, ERR_MALFORMED_REQUEST, from);
            return;
        }
        if (request.semantics != config_.semantics) {
            log_line("MISMATCH", "request", request.client_id, request.request_id,
                     request.opcode, "semantics-mismatch");
            send_uncached_error(request, ERR_SEMANTICS_MISMATCH, from);
            return;
        }
        if (request.client_id == 0 || request.request_id < 0 || request.status != OK
                || request.ack_up_to < NO_ACK || request.ack_up_to >= request.request_id) {
            send_uncached_error(request, ERR_INVALID_ARGUMENT, from);
            return;
        }

        prune_history(request.client_id, request.ack_up_to);
        const std::uint8_t* body = bytes + HEADER_SIZE;
        const std::size_t body_length = static_cast<std::size_t>(request.payload_len);
        const std::uint64_t request_fingerprint = fingerprint(request.opcode, body, body_length);

        if (config_.semantics == SEM_AT_MOST_ONCE) {
            if (HistoryEntry* cached = find_history(request.client_id, request.request_id)) {
                if (cached->fingerprint != request_fingerprint) {
                    log_line("CONFLICT", "reply", request.client_id, request.request_id,
                             request.opcode, "fingerprint-differs");
                    send_uncached_error(request, ERR_REQUEST_ID_CONFLICT, from);
                } else {
                    log_line("CACHE_HIT", "reply", request.client_id, request.request_id,
                             request.opcode, "replay");
                    send_reply_bytes(cached->reply.data(), cached->length, request, from);
                }
                return;
            }
            log_line("HISTORY", "request", request.client_id, request.request_id,
                     request.opcode, "miss");
        }

        const bool first_oracle_event = mark_first_event(request.client_id, request.request_id);
        std::array<std::uint8_t, MAX_BODY> reply_body{};
        std::size_t reply_body_length = 0;
        std::int32_t mutated_flight = 0;
        const std::int32_t status = dispatch(request, body, body_length, from,
                                             first_oracle_event, reply_body,
                                             reply_body_length, mutated_flight);
        std::array<std::uint8_t, MAX_DATAGRAM> reply{};
        const std::size_t reply_length = marshal_reply(request, status, reply_body.data(),
                                                       reply_body_length, reply);
        if (reply_length == 0) {
            send_uncached_error(request, ERR_SERVER_ERROR, from);
            return;
        }
        if (config_.semantics == SEM_AT_MOST_ONCE) {
            store_history(request.client_id, request.request_id, request_fingerprint,
                          reply.data(), reply_length);
            log_line("CACHE", "reply", request.client_id, request.request_id,
                     request.opcode, "stored-before-send");
        }
        send_reply_bytes(reply.data(), reply_length, request, from);

        // Reply loss happens after execution. A successful reservation must still
        // notify monitors, which is the live proof that the operation ran.
        if (status == OK && request.opcode == OP_RESERVE) {
            fire_callbacks(mutated_flight, request.request_id);
        }
        if (mutated_flight != 0) assert_expected_state(request, mutated_flight);
    }

    // The brief requires the server to print every request it receives and every reply it
    // produces. RECV and SEND carry header fields only, so these two lines add what was
    // actually asked and answered. An error reply has no values, and its SEND line already
    // names the status, so RESULT is written for successful replies only.
    void log_args(const Header& request, const std::string& args) {
        log_line("ARGS", "request", request.client_id, request.request_id, request.opcode,
                 "decoded", args);
    }

    void log_result(const Header& request, const std::string& result) {
        log_line("RESULT", "reply", request.client_id, request.request_id, request.opcode,
                 "OK", result);
    }

    std::int32_t dispatch(const Header& request, const std::uint8_t* body,
                          std::size_t body_length, const Endpoint& from,
                          bool first_oracle_event,
                          std::array<std::uint8_t, MAX_BODY>& reply_body,
                          std::size_t& reply_body_length,
                          std::int32_t& mutated_flight) {
        if (!valid_opcode(request.opcode)) return ERR_UNKNOWN_OPCODE;
        Reader in(body, body_length);
        Writer out(reply_body.data(), reply_body.size());
        std::int32_t status = OK;

        if (request.opcode == OP_QUERY_BY_ROUTE) {
            std::array<char, MAX_STRING_BYTES + 1> source{};
            std::array<char, MAX_STRING_BYTES + 1> destination{};
            std::size_t source_length = 0;
            std::size_t destination_length = 0;
            if (!in.get_string(source, source_length)
                    || !in.get_string(destination, destination_length)
                    || in.remaining() != 0) {
                return ERR_MALFORMED_REQUEST;
            }
            log_args(request, "source=" + quoted_place(source, source_length)
                     + " destination=" + quoted_place(destination, destination_length));
            if (source_length == 0 || destination_length == 0) return ERR_INVALID_ARGUMENT;
            std::array<std::int32_t, MAX_ROUTE_RESULTS> matches{};
            std::size_t count = 0;
            bool truncated = false;
            for (const Flight& flight : flights_) {
                if (normalized_equal(source, source_length, flight.source)
                        && normalized_equal(destination, destination_length,
                                            flight.destination)) {
                    if (count < matches.size()) matches[count++] = flight.id;
                    else truncated = true;
                }
            }
            if (count == 0) return ERR_NO_ROUTE_MATCH;
            std::sort(matches.begin(), matches.begin() + static_cast<std::ptrdiff_t>(count));
            out.put_i32(truncated ? 1 : 0);
            out.put_u32(static_cast<std::uint32_t>(count));
            std::string ids;
            for (std::size_t i = 0; i < count; ++i) {
                out.put_i32(matches[i]);
                ids += (i == 0 ? "" : ",") + std::to_string(matches[i]);
            }
            log_result(request, "truncated=" + std::to_string(truncated ? 1 : 0)
                       + " count=" + std::to_string(count) + " ids=" + ids);
        } else if (request.opcode == OP_QUERY_BY_ID) {
            std::int32_t id = 0;
            if (!in.get_i32(id) || in.remaining() != 0) return ERR_MALFORMED_REQUEST;
            log_args(request, "flight=" + std::to_string(id));
            Flight* flight = find_flight(id);
            if (flight == nullptr) return ERR_UNKNOWN_FLIGHT;
            out.put_i32(flight->departure.year);
            out.put_i32(flight->departure.month);
            out.put_i32(flight->departure.day);
            out.put_i32(flight->departure.hour);
            out.put_i32(flight->departure.minute);
            out.put_f32(flight->fare);
            out.put_i32(flight->seats);
            log_result(request, "departure=" + departure_text(flight->departure)
                       + " fare=" + fare_text(flight->fare)
                       + " seats=" + std::to_string(flight->seats));
        } else if (request.opcode == OP_RESERVE) {
            std::int32_t id = 0;
            std::int32_t seats = 0;
            if (!in.get_i32(id) || !in.get_i32(seats) || in.remaining() != 0) {
                return ERR_MALFORMED_REQUEST;
            }
            log_args(request, "flight=" + std::to_string(id) + " seats=" + std::to_string(seats));
            if (seats < 1) return ERR_INVALID_ARGUMENT;
            Flight* flight = find_flight(id);
            if (flight == nullptr) return ERR_UNKNOWN_FLIGHT;
            if (seats > flight->seats) return ERR_INSUFFICIENT_SEATS;
            const std::int32_t before = flight->seats;
            flight->seats -= seats;
            if (first_oracle_event) flight->expected_seats -= seats;
            mutated_flight = id;
            out.put_i32(flight->seats);
            log_line("EXECUTE", "request", request.client_id, request.request_id,
                     request.opcode, first_oracle_event ? "new" : "re-executed",
                     "seats=" + std::to_string(before) + "->" + std::to_string(flight->seats));
            log_result(request, "seats=" + std::to_string(flight->seats));
        } else if (request.opcode == OP_MONITOR) {
            std::int32_t id = 0;
            std::int32_t interval_ms = 0;
            if (!in.get_i32(id) || !in.get_i32(interval_ms) || in.remaining() != 0) {
                return ERR_MALFORMED_REQUEST;
            }
            log_args(request, "flight=" + std::to_string(id)
                     + " interval_ms=" + std::to_string(interval_ms));
            if (interval_ms < 1 || interval_ms > MAX_MONITOR_INTERVAL_MS) {
                return ERR_INVALID_ARGUMENT;
            }
            Flight* flight = find_flight(id);
            if (flight == nullptr) return ERR_UNKNOWN_FLIGHT;
            status = register_monitor(id, interval_ms, request, from);
            if (status == OK) {
                out.put_i32(flight->seats);
                out.put_i32(interval_ms);
                log_result(request, "seats=" + std::to_string(flight->seats)
                           + " expires_in_ms=" + std::to_string(interval_ms));
            }
        } else if (request.opcode == OP_ADJUST_FARE) {
            std::int32_t id = 0;
            std::uint32_t fare_bits = 0;
            if (!in.get_i32(id) || !in.get_u32(fare_bits) || in.remaining() != 0) {
                return ERR_MALFORMED_REQUEST;
            }
            const float delta = float_from_bits(fare_bits);
            log_args(request, "flight=" + std::to_string(id) + " delta=" + fare_text(delta));
            if (!std::isfinite(delta)) return ERR_INVALID_ARGUMENT;
            Flight* flight = find_flight(id);
            if (flight == nullptr) return ERR_UNKNOWN_FLIGHT;
            const float result = flight->fare + delta;
            if (!legal_fare(result)) return ERR_INVALID_FARE;
            const float before = flight->fare;
            flight->fare = result;
            if (first_oracle_event) flight->expected_fare += delta;
            mutated_flight = id;
            out.put_f32(flight->fare);
            log_line("EXECUTE", "request", request.client_id, request.request_id,
                     request.opcode, first_oracle_event ? "new" : "re-executed",
                     "fare=" + fare_text(before) + "->" + fare_text(flight->fare));
            log_result(request, "fare=" + fare_text(flight->fare));
        } else if (request.opcode == OP_SET_FARE) {
            std::int32_t id = 0;
            std::uint32_t fare_bits = 0;
            if (!in.get_i32(id) || !in.get_u32(fare_bits) || in.remaining() != 0) {
                return ERR_MALFORMED_REQUEST;
            }
            const float new_fare = float_from_bits(fare_bits);
            log_args(request, "flight=" + std::to_string(id)
                     + " new_fare=" + fare_text(new_fare));
            if (!std::isfinite(new_fare)) return ERR_INVALID_ARGUMENT;
            if (!legal_fare(new_fare)) return ERR_INVALID_FARE;
            Flight* flight = find_flight(id);
            if (flight == nullptr) return ERR_UNKNOWN_FLIGHT;
            const float before = flight->fare;
            flight->fare = new_fare;
            if (first_oracle_event) flight->expected_fare = new_fare;
            mutated_flight = id;
            out.put_f32(flight->fare);
            log_line("EXECUTE", "request", request.client_id, request.request_id,
                     request.opcode, first_oracle_event ? "new" : "re-executed",
                     "fare=" + fare_text(before) + "->" + fare_text(flight->fare));
            log_result(request, "fare=" + fare_text(flight->fare));
        }

        if (!out.ok()) return ERR_SERVER_ERROR;
        if (status == OK) reply_body_length = out.position();
        return status;
    }

    Flight* find_flight(std::int32_t id) {
        for (Flight& flight : flights_) if (flight.id == id) return &flight;
        return nullptr;
    }

    std::int32_t register_monitor(std::int32_t flight_id, std::int32_t interval_ms,
                                  const Header& request, const Endpoint& endpoint) {
        expire_monitors();
        Monitor* slot = nullptr;
        for (Monitor& monitor : monitors_) {
            if (monitor.used && monitor.client_id == request.client_id
                    && monitor.flight_id == flight_id) {
                slot = &monitor;
                break;
            }
            if (!monitor.used && slot == nullptr) slot = &monitor;
        }
        if (slot == nullptr) return ERR_MONITOR_TABLE_FULL;
        const bool replacing = slot->used;
        slot->used = true;
        slot->flight_id = flight_id;
        slot->client_id = request.client_id;
        slot->endpoint = endpoint;
        slot->expires_at = Clock::now() + std::chrono::milliseconds(interval_ms);
        slot->request_id = request.request_id;
        slot->semantics = request.semantics;
        slot->callback_seq = 0;
        log_line(replacing ? "SUB_UPDATE" : "SUB_ADD", "callback", request.client_id,
                 request.request_id, OP_MONITOR, endpoint_text(endpoint),
                 "flight=" + std::to_string(flight_id));
        return OK;
    }

    void fire_callbacks(std::int32_t flight_id, std::int32_t event_request_id) {
        expire_monitors();
        Flight* flight = find_flight(flight_id);
        if (flight == nullptr) return;
        for (Monitor& monitor : monitors_) {
            if (!monitor.used || monitor.flight_id != flight_id) continue;
            ++monitor.callback_seq;
            std::array<std::uint8_t, MAX_DATAGRAM> bytes{};
            Writer out(bytes.data(), bytes.size());
            const Header header = make_async(MSG_CALLBACK, monitor.client_id,
                                             monitor.request_id, monitor.semantics, 16);
            write_header(out, header);
            out.put_i32(flight_id);
            out.put_i32(flight->seats);
            out.put_i32(event_request_id);
            out.put_i32(monitor.callback_seq);
            if (!out.ok()) continue;
            if (loss_.drop_callback()) {
                log_line("DROP", "callback", monitor.client_id, monitor.request_id,
                         OP_NONE, loss_.reason(), "seq=" + std::to_string(monitor.callback_seq));
                if (loss_.reason() == "forced-first-callback") {
                    log_line("RULE", "callback", monitor.client_id, monitor.request_id,
                             OP_NONE, "RULE_CONSUMED");
                }
            } else {
                send_raw(bytes.data(), out.position(), monitor.endpoint);
                log_line("SEND", "callback", monitor.client_id, monitor.request_id,
                         OP_NONE, "transmitted", "seq=" + std::to_string(monitor.callback_seq));
            }
        }
    }

    void expire_monitors() {
        const auto now = Clock::now();
        for (Monitor& monitor : monitors_) {
            if (!monitor.used || monitor.expires_at > now) continue;
            std::array<std::uint8_t, MAX_DATAGRAM> bytes{};
            Writer out(bytes.data(), bytes.size());
            const Header header = make_async(MSG_MONITOR_END, monitor.client_id,
                                             monitor.request_id, monitor.semantics, 8);
            write_header(out, header);
            out.put_i32(monitor.flight_id);
            out.put_i32(monitor.callback_seq);
            if (out.ok()) send_raw(bytes.data(), out.position(), monitor.endpoint);
            log_line("SUB_EXPIRE", "callback", monitor.client_id, monitor.request_id,
                     OP_NONE, "monitor-end", "total=" + std::to_string(monitor.callback_seq));
            monitor.used = false;
        }
    }

    HistoryEntry* find_history(std::uint32_t client_id, std::int32_t request_id) {
        for (HistoryEntry& entry : history_) {
            if (entry.used && entry.client_id == client_id && entry.request_id == request_id) {
                return &entry;
            }
        }
        return nullptr;
    }

    void prune_history(std::uint32_t client_id, std::int32_t ack_up_to) {
        if (ack_up_to == NO_ACK) return;
        for (HistoryEntry& entry : history_) {
            if (entry.used && entry.client_id == client_id && entry.request_id <= ack_up_to) {
                log_line("EVICT", "reply", entry.client_id, entry.request_id, OP_NONE,
                         "ack-watermark");
                entry.used = false;
            }
        }
    }

    void evict_expired_history() {
        const auto now = Clock::now();
        for (HistoryEntry& entry : history_) {
            if (entry.used && now - entry.stored_at > HISTORY_TTL) {
                log_line("EVICT", "reply", entry.client_id, entry.request_id, OP_NONE,
                         "idle-ttl");
                entry.used = false;
            }
        }
    }

    void store_history(std::uint32_t client_id, std::int32_t request_id,
                       std::uint64_t request_fingerprint, const std::uint8_t* bytes,
                       std::size_t length) {
        HistoryEntry* slot = nullptr;
        std::size_t client_count = 0;
        HistoryEntry* oldest_client = nullptr;
        HistoryEntry* oldest_global = nullptr;
        for (HistoryEntry& entry : history_) {
            if (!entry.used && slot == nullptr) slot = &entry;
            if (entry.used) {
                if (oldest_global == nullptr || entry.order < oldest_global->order) {
                    oldest_global = &entry;
                }
                if (entry.client_id == client_id) {
                    ++client_count;
                    if (oldest_client == nullptr || entry.order < oldest_client->order) {
                        oldest_client = &entry;
                    }
                }
            }
        }
        if (client_count >= MAX_HISTORY_PER_CLIENT) slot = oldest_client;
        else if (slot == nullptr) slot = oldest_global;
        if (slot == nullptr) return;
        slot->used = true;
        slot->client_id = client_id;
        slot->request_id = request_id;
        slot->fingerprint = request_fingerprint;
        slot->length = length;
        std::copy(bytes, bytes + length, slot->reply.begin());
        slot->stored_at = Clock::now();
        slot->order = ++history_order_;
    }

    bool mark_first_event(std::uint32_t client_id, std::int32_t request_id) {
        OracleEvent* slot = nullptr;
        OracleEvent* oldest = nullptr;
        for (OracleEvent& event : oracle_events_) {
            if (event.used && event.client_id == client_id && event.request_id == request_id) {
                return false;
            }
            if (!event.used && slot == nullptr) slot = &event;
            if (event.used && (oldest == nullptr || event.order < oldest->order)) oldest = &event;
        }
        if (slot == nullptr) slot = oldest;
        if (slot != nullptr) {
            slot->used = true;
            slot->client_id = client_id;
            slot->request_id = request_id;
            slot->order = ++oracle_order_;
        }
        return true;
    }

    // Carries the request that caused the mutation, so a divergence line names the
    // client, request and operation that produced it (README sec. 10.2 forbids lines that
    // cannot be correlated).
    void assert_expected_state(const Header& request, std::int32_t flight_id) {
        Flight* flight = find_flight(flight_id);
        if (flight == nullptr) return;
        const bool seats_ok = flight->seats == flight->expected_seats;
        const bool fare_ok = std::memcmp(&flight->fare, &flight->expected_fare,
                                         sizeof(float)) == 0;
        log_line((seats_ok && fare_ok) ? "ORACLE_OK" : "ORACLE_ERROR", "state",
                 request.client_id, request.request_id, request.opcode,
                 (seats_ok && fare_ok) ? "matches" : "diverged",
                 "flight=" + std::to_string(flight_id)
                 + " seats=" + std::to_string(flight->seats)
                 + "/" + std::to_string(flight->expected_seats)
                 + " fare=" + fare_text(flight->fare)
                 + "/" + fare_text(flight->expected_fare));
    }

    std::size_t marshal_reply(const Header& request, std::int32_t status,
                              const std::uint8_t* body, std::size_t body_length,
                              std::array<std::uint8_t, MAX_DATAGRAM>& destination) {
        if (status != OK) body_length = 0;
        Writer out(destination.data(), destination.size());
        const Header reply = make_reply(request, status, static_cast<std::int32_t>(body_length));
        if (!write_header(out, reply)) return 0;
        if (HEADER_SIZE + body_length > destination.size()) return 0;
        if (body_length != 0) std::memcpy(destination.data() + HEADER_SIZE, body, body_length);
        return HEADER_SIZE + body_length;
    }

    void send_uncached_error(const Header& request, std::int32_t status,
                             const Endpoint& endpoint) {
        std::array<std::uint8_t, MAX_DATAGRAM> bytes{};
        Writer out(bytes.data(), bytes.size());
        write_header(out, make_reply(request, status, 0));
        if (out.ok()) send_reply_bytes(bytes.data(), out.position(), request, endpoint);
    }

    void send_reply_bytes(const std::uint8_t* bytes, std::size_t length,
                          const Header& request, const Endpoint& endpoint) {
        Header reply_header;
        Reader header_reader(bytes, length);
        read_header(header_reader, reply_header);
        if (loss_.drop_reply(request.opcode)) {
            log_line("DROP", "reply", request.client_id, request.request_id,
                     request.opcode, loss_.reason(), status_name(reply_header.status));
            if (loss_.reason() == "forced-first-reply") {
                log_line("RULE", "reply", request.client_id, request.request_id,
                         request.opcode, "RULE_CONSUMED");
            }
            return;
        }
        send_raw(bytes, length, endpoint);
        log_line("SEND", "reply", request.client_id, request.request_id,
                 request.opcode, "transmitted", status_name(reply_header.status));
    }

    void send_raw(const std::uint8_t* bytes, std::size_t length, const Endpoint& endpoint) {
#ifdef _WIN32
        const int sent = sendto(socket_, reinterpret_cast<const char*>(bytes),
                                static_cast<int>(length), 0,
                                reinterpret_cast<const sockaddr*>(&endpoint.address),
                                endpoint.length);
        if (sent == SOCKET_ERROR)
#else
        const ssize_t sent = sendto(socket_, bytes, length, 0,
                                    reinterpret_cast<const sockaddr*>(&endpoint.address),
                                    endpoint.length);
        if (sent < 0)
#endif
            std::cerr << "sendto failed for " << endpoint_text(endpoint)
                      << ": " << socket_error() << std::endl;
    }

    SocketHandle socket_;
    Config config_;
    LossSimulator loss_;
    std::array<Flight, 6> flights_;
    std::array<Monitor, MAX_MONITORS> monitors_{};
    std::array<HistoryEntry, MAX_HISTORY> history_{};
    std::array<OracleEvent, MAX_ORACLE_EVENTS> oracle_events_{};
    std::uint64_t history_order_ = 0;
    std::uint64_t oracle_order_ = 0;
};

SocketHandle bind_socket(std::uint16_t port) {
    const SocketHandle socket_handle = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket_handle == INVALID_SOCKET_HANDLE) {
        throw std::runtime_error("socket failed: " + socket_error());
    }
    int reuse = 1;
#ifdef _WIN32
    setsockopt(socket_handle, SOL_SOCKET, SO_REUSEADDR,
               reinterpret_cast<const char*>(&reuse), sizeof(reuse));
#else
    setsockopt(socket_handle, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
#endif
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(port);
    if (bind(socket_handle, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0) {
        const std::string error = socket_error();
        close_socket(socket_handle);
        throw std::runtime_error("bind failed: " + error);
    }
    return socket_handle;
}

void print_banner(const Config& config) {
    std::cout << "SC6103 Flight Information Server\n"
              << "--------------------------------------------------------------\n"
              << "  bind        0.0.0.0:" << config.port << '\n'
              << "  semantics   " << (config.semantics == SEM_AT_MOST_ONCE
                                         ? "at-most-once" : "at-least-once") << '\n'
              << "  loss        request " << std::fixed << std::setprecision(2)
              << config.drop_request << " reply " << config.drop_reply
              << " callback " << config.drop_callback << '\n'
              << "  rules       ";
    if (config.drop_first_reply_opcode == OP_NONE && !config.drop_first_callback) {
        std::cout << "none";
    } else {
        if (config.drop_first_reply_opcode != OP_NONE) {
            std::cout << "drop-first-reply " << opcode_name(config.drop_first_reply_opcode);
        }
        if (config.drop_first_callback) {
            if (config.drop_first_reply_opcode != OP_NONE) std::cout << ' ';
            std::cout << "drop-first-callback";
        }
    }
    std::cout << '\n'
              << "  seed        " << config.seed << '\n'
              << "  history     " << MAX_HISTORY_PER_CLIENT << "/client, ttl "
              << std::chrono::duration_cast<std::chrono::seconds>(HISTORY_TTL).count() << "s\n"
              << "--------------------------------------------------------------\n"
              << "Seeded flights:\n";
    for (const Flight& flight : seed_flights()) {
        std::cout << "  " << std::setfill('0') << std::setw(4) << flight.id
                  << std::setfill(' ') << "  " << flight.source << " -> "
                  << flight.destination << "  fare=" << std::setprecision(2)
                  << flight.fare << " seats=" << flight.seats << '\n';
    }
    std::cout << std::flush;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        const Config config = parse_config(argc, argv);
#ifdef _WIN32
        WSADATA data{};
        if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
            throw std::runtime_error("WSAStartup failed");
        }
#endif
        std::signal(SIGINT, stop_server);
        std::signal(SIGTERM, stop_server);
        const SocketHandle socket_handle = bind_socket(config.port);
        print_banner(config);
        Server(socket_handle, config).run();
        close_socket(socket_handle);
#ifdef _WIN32
        WSACleanup();
#endif
        std::cout << "Server stopped." << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "ERROR: " << error.what() << "\n\n" << usage_text();
        return 2;
    }
}
