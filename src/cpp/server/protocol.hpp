#ifndef SC6103_PROTOCOL_HPP
#define SC6103_PROTOCOL_HPP

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace sc6103 {

constexpr std::uint32_t MAGIC = 0x53433631U;
constexpr std::int32_t VERSION = 1;
constexpr std::int32_t MSG_REQUEST = 0;
constexpr std::int32_t MSG_REPLY = 1;
constexpr std::int32_t MSG_CALLBACK = 2;
constexpr std::int32_t MSG_MONITOR_END = 3;
constexpr std::int32_t SEM_AT_LEAST_ONCE = 0;
constexpr std::int32_t SEM_AT_MOST_ONCE = 1;
constexpr std::int32_t OP_NONE = 0;
constexpr std::int32_t OP_QUERY_BY_ROUTE = 1;
constexpr std::int32_t OP_QUERY_BY_ID = 2;
constexpr std::int32_t OP_RESERVE = 3;
constexpr std::int32_t OP_MONITOR = 4;
constexpr std::int32_t OP_ADJUST_FARE = 5;
constexpr std::int32_t OP_SET_FARE = 6;

constexpr std::int32_t OK = 0;
constexpr std::int32_t ERR_UNKNOWN_FLIGHT = 1;
constexpr std::int32_t ERR_NO_ROUTE_MATCH = 2;
constexpr std::int32_t ERR_INSUFFICIENT_SEATS = 3;
constexpr std::int32_t ERR_INVALID_ARGUMENT = 4;
constexpr std::int32_t ERR_INVALID_FARE = 5;
constexpr std::int32_t ERR_MALFORMED_REQUEST = 6;
constexpr std::int32_t ERR_UNKNOWN_OPCODE = 7;
constexpr std::int32_t ERR_MONITOR_TABLE_FULL = 8;
constexpr std::int32_t ERR_SERVER_ERROR = 9;
constexpr std::int32_t ERR_VERSION_MISMATCH = 10;
constexpr std::int32_t ERR_SEMANTICS_MISMATCH = 11;
constexpr std::int32_t ERR_REQUEST_ID_CONFLICT = 12;

constexpr std::size_t MAX_DATAGRAM = 512;
constexpr std::size_t HEADER_SIZE = 40;
constexpr std::size_t MAX_BODY = MAX_DATAGRAM - HEADER_SIZE;
constexpr std::size_t MAX_STRING_BYTES = 63;
constexpr std::size_t MAX_ROUTE_RESULTS = 64;
constexpr std::int32_t MAX_MONITOR_INTERVAL_MS = 300000;
constexpr float MAX_FARE = 100000.0F;
constexpr std::int32_t NO_ACK = -1;

inline bool valid_message_type(std::int32_t value) {
    return value >= MSG_REQUEST && value <= MSG_MONITOR_END;
}

inline bool valid_semantics(std::int32_t value) {
    return value == SEM_AT_LEAST_ONCE || value == SEM_AT_MOST_ONCE;
}

inline bool valid_opcode(std::int32_t value) {
    return value >= OP_QUERY_BY_ROUTE && value <= OP_SET_FARE;
}

inline bool legal_fare(float value) {
    return std::isfinite(value) && value > 0.0F && value <= MAX_FARE;
}

inline const char* opcode_name(std::int32_t value) {
    switch (value) {
        // CALLBACK, MONITOR_END and history evictions carry no operation. "NONE" is the
        // word the Java client logs for the same placeholder, so the two logs line up.
        case OP_NONE: return "NONE";
        case OP_QUERY_BY_ROUTE: return "QUERY_BY_ROUTE";
        case OP_QUERY_BY_ID: return "QUERY_BY_ID";
        case OP_RESERVE: return "RESERVE";
        case OP_MONITOR: return "MONITOR";
        case OP_ADJUST_FARE: return "ADJUST_FARE";
        case OP_SET_FARE: return "SET_FARE";
        default: return "UNKNOWN";
    }
}

inline const char* status_name(std::int32_t value) {
    switch (value) {
        case OK: return "OK";
        case ERR_UNKNOWN_FLIGHT: return "ERR_UNKNOWN_FLIGHT";
        case ERR_NO_ROUTE_MATCH: return "ERR_NO_ROUTE_MATCH";
        case ERR_INSUFFICIENT_SEATS: return "ERR_INSUFFICIENT_SEATS";
        case ERR_INVALID_ARGUMENT: return "ERR_INVALID_ARGUMENT";
        case ERR_INVALID_FARE: return "ERR_INVALID_FARE";
        case ERR_MALFORMED_REQUEST: return "ERR_MALFORMED_REQUEST";
        case ERR_UNKNOWN_OPCODE: return "ERR_UNKNOWN_OPCODE";
        case ERR_MONITOR_TABLE_FULL: return "ERR_MONITOR_TABLE_FULL";
        case ERR_SERVER_ERROR: return "ERR_SERVER_ERROR";
        case ERR_VERSION_MISMATCH: return "ERR_VERSION_MISMATCH";
        case ERR_SEMANTICS_MISMATCH: return "ERR_SEMANTICS_MISMATCH";
        case ERR_REQUEST_ID_CONFLICT: return "ERR_REQUEST_ID_CONFLICT";
        default: return "UNKNOWN_STATUS";
    }
}

class Writer {
public:
    Writer(std::uint8_t* data, std::size_t capacity)
        : data_(data), capacity_(capacity), position_(0), ok_(true) {}

    bool put_u32(std::uint32_t value) {
        if (!reserve(4)) return false;
        data_[position_++] = static_cast<std::uint8_t>((value >> 24U) & 0xFFU);
        data_[position_++] = static_cast<std::uint8_t>((value >> 16U) & 0xFFU);
        data_[position_++] = static_cast<std::uint8_t>((value >> 8U) & 0xFFU);
        data_[position_++] = static_cast<std::uint8_t>(value & 0xFFU);
        return true;
    }

    bool put_i32(std::int32_t value) {
        return put_u32(static_cast<std::uint32_t>(value));
    }

    bool put_f32(float value) {
        if (!std::isfinite(value)) {
            ok_ = false;
            return false;
        }
        static_assert(sizeof(float) == sizeof(std::uint32_t), "float32 is required");
        std::uint32_t bits = 0;
        // memcpy is intentional: type-punning through a cast would be undefined.
        std::memcpy(&bits, &value, sizeof(bits));
        return put_u32(bits);
    }

    bool put_string(const char* value, std::size_t length) {
        if (length > MAX_STRING_BYTES || !put_u32(static_cast<std::uint32_t>(length))) {
            ok_ = false;
            return false;
        }
        const std::size_t padding = (4U - (length % 4U)) % 4U;
        if (!reserve(length + padding)) return false;
        if (length != 0) std::memcpy(data_ + position_, value, length);
        position_ += length;
        for (std::size_t i = 0; i < padding; ++i) data_[position_++] = 0;
        return true;
    }

    std::size_t position() const { return position_; }
    bool ok() const { return ok_; }

private:
    bool reserve(std::size_t count) {
        if (!ok_ || count > capacity_ - position_) {
            ok_ = false;
            return false;
        }
        return true;
    }

    std::uint8_t* data_;
    std::size_t capacity_;
    std::size_t position_;
    bool ok_;
};

class Reader {
public:
    Reader(const std::uint8_t* data, std::size_t length)
        : data_(data), length_(length), position_(0), ok_(true) {}

    bool get_u32(std::uint32_t& value) {
        if (!reserve(4)) return false;
        value = (static_cast<std::uint32_t>(data_[position_]) << 24U)
              | (static_cast<std::uint32_t>(data_[position_ + 1]) << 16U)
              | (static_cast<std::uint32_t>(data_[position_ + 2]) << 8U)
              | static_cast<std::uint32_t>(data_[position_ + 3]);
        position_ += 4;
        return true;
    }

    bool get_i32(std::int32_t& value) {
        std::uint32_t bits = 0;
        if (!get_u32(bits)) return false;
        value = static_cast<std::int32_t>(bits);
        return true;
    }

    bool get_f32(float& value) {
        std::uint32_t bits = 0;
        if (!get_u32(bits)) return false;
        std::memcpy(&value, &bits, sizeof(value));
        if (!std::isfinite(value)) {
            ok_ = false;
            return false;
        }
        return true;
    }

    bool get_string(std::array<char, MAX_STRING_BYTES + 1>& value,
                    std::size_t& out_length) {
        std::uint32_t raw_length = 0;
        if (!get_u32(raw_length) || raw_length > MAX_STRING_BYTES) {
            ok_ = false;
            return false;
        }
        const std::size_t length = static_cast<std::size_t>(raw_length);
        const std::size_t padding = (4U - (length % 4U)) % 4U;
        if (!reserve(length + padding) || !valid_utf8(data_ + position_, length)) {
            ok_ = false;
            return false;
        }
        if (length != 0) std::memcpy(value.data(), data_ + position_, length);
        value[length] = '\0';
        position_ += length + padding;
        out_length = length;
        return true;
    }

    std::size_t remaining() const { return position_ <= length_ ? length_ - position_ : 0; }
    std::size_t position() const { return position_; }
    bool ok() const { return ok_; }

private:
    static bool valid_utf8(const std::uint8_t* bytes, std::size_t length) {
        std::size_t i = 0;
        while (i < length) {
            const std::uint8_t c = bytes[i++];
            if (c <= 0x7FU) continue;
            std::uint32_t codepoint = 0;
            std::size_t continuation = 0;
            if (c >= 0xC2U && c <= 0xDFU) {
                codepoint = c & 0x1FU;
                continuation = 1;
            } else if (c >= 0xE0U && c <= 0xEFU) {
                codepoint = c & 0x0FU;
                continuation = 2;
            } else if (c >= 0xF0U && c <= 0xF4U) {
                codepoint = c & 0x07U;
                continuation = 3;
            } else {
                return false;
            }
            if (continuation > length - i) return false;
            for (std::size_t j = 0; j < continuation; ++j) {
                const std::uint8_t next = bytes[i++];
                if ((next & 0xC0U) != 0x80U) return false;
                codepoint = (codepoint << 6U) | (next & 0x3FU);
            }
            if ((continuation == 2 && codepoint < 0x800U)
                    || (continuation == 3 && codepoint < 0x10000U)
                    || codepoint > 0x10FFFFU
                    || (codepoint >= 0xD800U && codepoint <= 0xDFFFU)) {
                return false;
            }
        }
        return true;
    }

    bool reserve(std::size_t count) {
        if (!ok_ || position_ > length_ || count > length_ - position_) {
            ok_ = false;
            return false;
        }
        return true;
    }

    const std::uint8_t* data_;
    std::size_t length_;
    std::size_t position_;
    bool ok_;
};

struct Header {
    std::uint32_t magic = MAGIC;
    std::int32_t version = VERSION;
    std::int32_t message_type = MSG_REQUEST;
    std::int32_t opcode = OP_NONE;
    std::int32_t semantics = SEM_AT_MOST_ONCE;
    std::uint32_t client_id = 0;
    std::int32_t request_id = 0;
    std::int32_t ack_up_to = NO_ACK;
    std::int32_t status = OK;
    std::int32_t payload_len = 0;
};

inline bool write_header(Writer& out, const Header& header) {
    const std::size_t start = out.position();
    return out.put_u32(header.magic)
        && out.put_i32(header.version)
        && out.put_i32(header.message_type)
        && out.put_i32(header.opcode)
        && out.put_i32(header.semantics)
        && out.put_u32(header.client_id)
        && out.put_i32(header.request_id)
        && out.put_i32(header.ack_up_to)
        && out.put_i32(header.status)
        && out.put_i32(header.payload_len)
        && out.position() - start == HEADER_SIZE;
}

inline bool read_header(Reader& in, Header& header) {
    return in.get_u32(header.magic)
        && in.get_i32(header.version)
        && in.get_i32(header.message_type)
        && in.get_i32(header.opcode)
        && in.get_i32(header.semantics)
        && in.get_u32(header.client_id)
        && in.get_i32(header.request_id)
        && in.get_i32(header.ack_up_to)
        && in.get_i32(header.status)
        && in.get_i32(header.payload_len);
}

inline Header make_reply(const Header& request, std::int32_t status,
                         std::int32_t payload_length) {
    Header reply;
    reply.message_type = MSG_REPLY;
    reply.opcode = request.opcode;
    reply.semantics = request.semantics;
    reply.client_id = request.client_id;
    reply.request_id = request.request_id;
    reply.ack_up_to = NO_ACK;
    reply.status = status;
    reply.payload_len = payload_length;
    return reply;
}

inline Header make_async(std::int32_t message_type, std::uint32_t client_id,
                         std::int32_t monitor_request_id, std::int32_t semantics,
                         std::int32_t payload_length) {
    Header result;
    result.message_type = message_type;
    result.opcode = OP_NONE;
    result.semantics = semantics;
    result.client_id = client_id;
    result.request_id = monitor_request_id;
    result.ack_up_to = NO_ACK;
    result.status = OK;
    result.payload_len = payload_length;
    return result;
}

}  // namespace sc6103

#endif
