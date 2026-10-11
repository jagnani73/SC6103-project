// C++ side of the conformance contract: every vector in tests/conformance/vectors.md,
// byte for byte, in both directions.
//
// CLAUDE.md makes the vectors "the contract" between two implementations that share no
// code. Each full-datagram vector is therefore encoded with the server's own Writer and
// header factories and compared against the hex copied from vectors.md, then the same hex
// is decoded and every field is checked. Floats are compared by bit pattern, because
// -0.0f == 0.0f would pass a dropped sign bit (vectors.md, "How to use this file").

#include "../../src/cpp/server/protocol.hpp"

#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

namespace {

using namespace sc6103;
using Bytes = std::vector<std::uint8_t>;

int checks = 0;
int failures = 0;

void check(bool condition, const std::string& label) {
    ++checks;
    if (condition) std::cout << "PASS " << label << '\n';
    else {
        ++failures;
        std::cerr << "FAIL " << label << '\n';
    }
}

// Parses hex exactly as vectors.md prints it, so a vector is pasted rather than retyped.
Bytes hex(const char* text) {
    Bytes bytes;
    int high = -1;
    for (const char* p = text; *p != '\0'; ++p) {
        int nibble = -1;
        if (*p >= '0' && *p <= '9') nibble = *p - '0';
        else if (*p >= 'A' && *p <= 'F') nibble = *p - 'A' + 10;
        else if (*p >= 'a' && *p <= 'f') nibble = *p - 'a' + 10;
        else continue;
        if (high < 0) {
            high = nibble;
        } else {
            bytes.push_back(static_cast<std::uint8_t>((high << 4) | nibble));
            high = -1;
        }
    }
    return bytes;
}

// The buffer starts non-zero so padding the encoder forgot to write cannot pass as zeros.
template <typename Encode>
Bytes encode(Encode encode_fn) {
    Bytes buffer(MAX_DATAGRAM, 0xAA);
    Writer out(buffer.data(), buffer.size());
    encode_fn(out);
    if (!out.ok()) return {};
    buffer.resize(out.position());
    return buffer;
}

std::uint32_t float_bits(float value) {
    std::uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

bool same_header(const Header& a, const Header& b) {
    return a.magic == b.magic && a.version == b.version
        && a.message_type == b.message_type && a.opcode == b.opcode
        && a.semantics == b.semantics && a.client_id == b.client_id
        && a.request_id == b.request_id && a.ack_up_to == b.ack_up_to
        && a.status == b.status && a.payload_len == b.payload_len;
}

// vectors.md "How to use": payloadLen must equal the datagram length minus 40.
bool length_agrees(const Bytes& datagram, const Header& header) {
    return header.payload_len >= 0
        && datagram.size() == HEADER_SIZE + static_cast<std::size_t>(header.payload_len);
}

std::string text_of(const std::array<char, MAX_STRING_BYTES + 1>& value, std::size_t length) {
    return std::string(value.data(), length);
}

// Shared header of V3 and V7: the querying client of vectors.md "Conventions".
Header querying_request(std::int32_t opcode, std::int32_t request_id, std::int32_t ack_up_to,
                        std::int32_t payload_len) {
    Header header;
    header.message_type = MSG_REQUEST;
    header.opcode = opcode;
    header.semantics = SEM_AT_MOST_ONCE;
    header.client_id = 0xA1B2C3D4U;
    header.request_id = request_id;
    header.ack_up_to = ack_up_to;
    header.status = OK;
    header.payload_len = payload_len;
    return header;
}

void test_v1_anchor() {
    const Bytes expected = hex(
        "00 00 07 8E 00 00 00 09 53 69 6E 67 61 70 6F 72"
        "65 00 00 00 00 00 00 06 4C 6F 6E 64 6F 6E 00 00"
        "00 00 07 EA 00 00 00 0A 00 00 00 0E 00 00 00 09"
        "00 00 00 1E 44 60 E0 00 00 00 00 78");
    const Bytes actual = encode([](Writer& out) {
        out.put_i32(1934);
        out.put_string("Singapore", 9);
        out.put_string("London", 6);
        out.put_i32(2026);
        out.put_i32(10);
        out.put_i32(14);
        out.put_i32(9);
        out.put_i32(30);
        out.put_f32(899.5F);
        out.put_i32(120);
    });
    check(actual.size() == 60, "V1 flight record is exactly 60 bytes");
    check(actual == expected, "V1 flight record encodes to the vectors.md bytes");

    Reader in(expected.data(), expected.size());
    std::int32_t id = 0;
    std::array<char, MAX_STRING_BYTES + 1> source{};
    std::array<char, MAX_STRING_BYTES + 1> destination{};
    std::size_t source_length = 0;
    std::size_t destination_length = 0;
    std::array<std::int32_t, 5> time{};
    float fare = 0.0F;
    std::int32_t seats = 0;
    bool ok = in.get_i32(id) && in.get_string(source, source_length)
        && in.get_string(destination, destination_length);
    for (std::int32_t& field : time) ok = ok && in.get_i32(field);
    ok = ok && in.get_f32(fare) && in.get_i32(seats) && in.remaining() == 0;
    check(ok && id == 1934 && text_of(source, source_length) == "Singapore"
              && text_of(destination, destination_length) == "London"
              && time == std::array<std::int32_t, 5>{{2026, 10, 14, 9, 30}}
              && float_bits(fare) == 0x4460E000U && seats == 120,
          "V1 flight record decodes field by field");
}

void test_v2_primitives() {
    struct IntCase { std::int32_t value; const char* bytes; const char* label; };
    const IntCase ints[] = {
        {0, "00 00 00 00", "0"},
        {1, "00 00 00 01", "1"},
        {-1, "FF FF FF FF", "-1"},
        {std::numeric_limits<std::int32_t>::max(), "7F FF FF FF", "INT32_MAX"},
        {std::numeric_limits<std::int32_t>::min(), "80 00 00 00", "INT32_MIN"},
    };
    for (const IntCase& c : ints) {
        const Bytes expected = hex(c.bytes);
        check(encode([&](Writer& out) { out.put_i32(c.value); }) == expected,
              std::string("V2.1 int32 ") + c.label + " encodes");
        Reader in(expected.data(), expected.size());
        std::int32_t decoded = 0;
        check(in.get_i32(decoded) && decoded == c.value,
              std::string("V2.1 int32 ") + c.label + " decodes");
    }

    struct UIntCase { std::uint32_t value; const char* bytes; const char* label; };
    const UIntCase uints[] = {
        {0U, "00 00 00 00", "0"},
        {0xFFFFFFFFU, "FF FF FF FF", "UINT32_MAX"},
    };
    for (const UIntCase& c : uints) {
        const Bytes expected = hex(c.bytes);
        check(encode([&](Writer& out) { out.put_u32(c.value); }) == expected,
              std::string("V2.2 uint32 ") + c.label + " encodes");
        Reader in(expected.data(), expected.size());
        std::uint32_t decoded = 0;
        check(in.get_u32(decoded) && decoded == c.value,
              std::string("V2.2 uint32 ") + c.label + " decodes");
    }

    struct FloatCase { float value; const char* bytes; const char* label; };
    const FloatCase floats[] = {
        {0.0F, "00 00 00 00", "0.0f"},
        {-0.0F, "80 00 00 00", "-0.0f"},
        {1.0F, "3F 80 00 00", "1.0f"},
        {-1.0F, "BF 80 00 00", "-1.0f"},
        {899.5F, "44 60 E0 00", "899.5f"},
        {0.1F, "3D CC CC CD", "0.1f"},
    };
    for (const FloatCase& c : floats) {
        const Bytes expected = hex(c.bytes);
        check(encode([&](Writer& out) { out.put_f32(c.value); }) == expected,
              std::string("V2.3 float32 ") + c.label + " encodes");
        Reader in(expected.data(), expected.size());
        float decoded = 1.0F;
        check(in.get_f32(decoded) && float_bits(decoded) == float_bits(c.value),
              std::string("V2.3 float32 ") + c.label + " decodes to the same bits");
    }

    const std::string longest =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789A";
    struct StringCase { std::string value; const char* bytes; const char* label; };
    const StringCase strings[] = {
        {"", "00 00 00 00", "empty"},
        {"A", "00 00 00 01 41 00 00 00", "\"A\" with 3 pad"},
        {"abc", "00 00 00 03 61 62 63 00", "\"abc\" with 1 pad"},
        {"abcd", "00 00 00 04 61 62 63 64", "\"abcd\" with no pad"},
        {longest,
         "00 00 00 3F 41 42 43 44 45 46 47 48 49 4A 4B 4C 4D 4E 4F 50"
         "51 52 53 54 55 56 57 58 59 5A 61 62 63 64 65 66"
         "67 68 69 6A 6B 6C 6D 6E 6F 70 71 72 73 74 75 76"
         "77 78 79 7A 30 31 32 33 34 35 36 37 38 39 41 00",
         "63-byte maximum"},
    };
    for (const StringCase& c : strings) {
        const Bytes expected = hex(c.bytes);
        check(encode([&](Writer& out) { out.put_string(c.value.data(), c.value.size()); })
                  == expected,
              std::string("V2.4 string ") + c.label + " encodes");
        Reader in(expected.data(), expected.size());
        std::array<char, MAX_STRING_BYTES + 1> decoded{};
        std::size_t length = 0;
        check(in.get_string(decoded, length) && text_of(decoded, length) == c.value
                  && in.remaining() == 0,
              std::string("V2.4 string ") + c.label + " decodes");
    }

    // Decoder-side rules from V2.4 that have no encoding of their own.
    std::array<char, MAX_STRING_BYTES + 1> decoded{};
    std::size_t length = 0;
    const Bytes nonzero_pad = hex("00 00 00 01 41 FF FF FF");
    Reader pad_reader(nonzero_pad.data(), nonzero_pad.size());
    check(pad_reader.get_string(decoded, length) && text_of(decoded, length) == "A",
          "V2.4 nonzero padding is ignored on read");

    Bytes too_long(4 + 64 + 4, 'A');
    too_long[0] = 0x00;
    too_long[1] = 0x00;
    too_long[2] = 0x00;
    too_long[3] = 0x40;
    Reader long_reader(too_long.data(), too_long.size());
    check(!long_reader.get_string(decoded, length), "V2.4 string length 64 is rejected");

    const Bytes negative_length = hex("FF FF FF FF 41 41 41 41");
    Reader negative_reader(negative_length.data(), negative_length.size());
    check(!negative_reader.get_string(decoded, length),
          "V2.4 string length with the top bit set is rejected");
}

const Header kV3Request = querying_request(OP_QUERY_BY_ROUTE, 7, 6, 28);

void test_v3_route_request() {
    const Bytes expected = hex(
        "53 43 36 31 00 00 00 01 00 00 00 00 00 00 00 01"
        "00 00 00 01 A1 B2 C3 D4 00 00 00 07 00 00 00 06"
        "00 00 00 00 00 00 00 1C 00 00 00 09 53 69 6E 67"
        "61 70 6F 72 65 00 00 00 00 00 00 06 4C 6F 6E 64"
        "6F 6E 00 00");
    const Bytes actual = encode([](Writer& out) {
        write_header(out, kV3Request);
        out.put_string("Singapore", 9);
        out.put_string("London", 6);
    });
    check(actual == expected, "V3 QUERY_BY_ROUTE request encodes to 68 bytes");

    Reader in(expected.data(), expected.size());
    Header header;
    std::array<char, MAX_STRING_BYTES + 1> source{};
    std::array<char, MAX_STRING_BYTES + 1> destination{};
    std::size_t source_length = 0;
    std::size_t destination_length = 0;
    const bool ok = read_header(in, header) && in.get_string(source, source_length)
        && in.get_string(destination, destination_length) && in.remaining() == 0;
    check(ok && same_header(header, kV3Request) && length_agrees(expected, header)
              && text_of(source, source_length) == "Singapore"
              && text_of(destination, destination_length) == "London",
          "V3 decodes to the same header and place names");
}

void test_v4_route_reply() {
    const Bytes expected = hex(
        "53 43 36 31 00 00 00 01 00 00 00 01 00 00 00 01"
        "00 00 00 01 A1 B2 C3 D4 00 00 00 07 FF FF FF FF"
        "00 00 00 00 00 00 00 14 00 00 00 00 00 00 00 03"
        "00 00 00 58 00 00 07 8E 00 00 08 99");
    const Header reply = make_reply(kV3Request, OK, 20);
    const Bytes actual = encode([&](Writer& out) {
        write_header(out, reply);
        out.put_i32(0);
        out.put_u32(3);
        out.put_i32(88);
        out.put_i32(1934);
        out.put_i32(2201);
    });
    check(actual == expected, "V4 route reply built by make_reply encodes to 60 bytes");

    Reader in(expected.data(), expected.size());
    Header header;
    std::int32_t truncated = -1;
    std::uint32_t count = 0;
    std::array<std::int32_t, 3> ids{};
    bool ok = read_header(in, header) && in.get_i32(truncated) && in.get_u32(count);
    for (std::int32_t& id : ids) ok = ok && in.get_i32(id);
    check(ok && in.remaining() == 0 && same_header(header, reply)
              && length_agrees(expected, header) && truncated == 0 && count == 3
              && ids == std::array<std::int32_t, 3>{{88, 1934, 2201}},
          "V4 decodes with ackUpTo -1 and ascending ids");
}

void test_v5_callback() {
    const Bytes expected = hex(
        "53 43 36 31 00 00 00 01 00 00 00 02 00 00 00 00"
        "00 00 00 01 0F 0E 0D 0C 00 00 00 0B FF FF FF FF"
        "00 00 00 00 00 00 00 14 00 00 07 8E 00 00 00 75"
        "A1 B2 C3 D4 00 00 00 16 00 00 00 01");
    const Header callback = make_async(MSG_CALLBACK, 0x0F0E0D0CU, 11, SEM_AT_MOST_ONCE, 20);
    const Bytes actual = encode([&](Writer& out) {
        write_header(out, callback);
        out.put_i32(1934);
        out.put_i32(117);
        out.put_u32(0xA1B2C3D4U);
        out.put_i32(22);
        out.put_i32(1);
    });
    check(actual == expected, "V5 callback built by make_async encodes to 60 bytes");

    Reader in(expected.data(), expected.size());
    Header header;
    std::int32_t flight = 0;
    std::int32_t seats = 0;
    std::uint32_t event_client = 0;
    std::int32_t event_request = 0;
    std::int32_t seq = 0;
    const bool ok = read_header(in, header) && in.get_i32(flight) && in.get_i32(seats)
        && in.get_u32(event_client) && in.get_i32(event_request) && in.get_i32(seq);
    check(ok && in.remaining() == 0 && same_header(header, callback)
              && length_agrees(expected, header)
              && flight == 1934 && seats == 117 && event_client == 0xA1B2C3D4U
              && event_request == 22 && seq == 1,
          "V5 decodes with the event named by client 0xA1B2C3D4 and request 22");
}

void test_v6_monitor_end() {
    const Bytes expected = hex(
        "53 43 36 31 00 00 00 01 00 00 00 03 00 00 00 00"
        "00 00 00 01 0F 0E 0D 0C 00 00 00 0B FF FF FF FF"
        "00 00 00 00 00 00 00 08 00 00 07 8E 00 00 00 04");
    const Header end = make_async(MSG_MONITOR_END, 0x0F0E0D0CU, 11, SEM_AT_MOST_ONCE, 8);
    const Bytes actual = encode([&](Writer& out) {
        write_header(out, end);
        out.put_i32(1934);
        out.put_i32(4);
    });
    check(actual == expected, "V6 MONITOR_END built by make_async encodes to 48 bytes");

    Reader in(expected.data(), expected.size());
    Header header;
    std::int32_t flight = 0;
    std::int32_t total = 0;
    const bool ok = read_header(in, header) && in.get_i32(flight) && in.get_i32(total);
    check(ok && in.remaining() == 0 && same_header(header, end)
              && length_agrees(expected, header) && flight == 1934 && total == 4,
          "V6 decodes with the final callback count");
}

void test_v7_error_reply() {
    const Bytes expected = hex(
        "53 43 36 31 00 00 00 01 00 00 00 01 00 00 00 02"
        "00 00 00 01 A1 B2 C3 D4 00 00 00 08 FF FF FF FF"
        "00 00 00 01 00 00 00 00");
    const Header request = querying_request(OP_QUERY_BY_ID, 8, 7, 4);
    const Header reply = make_reply(request, ERR_UNKNOWN_FLIGHT, 0);
    const Bytes actual = encode([&](Writer& out) { write_header(out, reply); });
    check(actual == expected, "V7 error reply is a bare 40-byte header");

    Reader in(expected.data(), expected.size());
    Header header;
    check(read_header(in, header) && in.remaining() == 0 && same_header(header, reply)
              && length_agrees(expected, header),
          "V7 decodes with status 1 and no body");
}

// Rules the codec enforces that no vector can express, because a malformed input has no
// correct encoding.
void test_codec_rejections() {
    std::array<std::uint8_t, 8> bytes{};
    Writer out(bytes.data(), bytes.size());
    check(!out.put_f32(std::numeric_limits<float>::infinity()),
          "codec refuses to encode infinity");

    const Bytes overlong_utf8 = hex("00 00 00 02 C0 80 00 00");
    Reader bad_string(overlong_utf8.data(), overlong_utf8.size());
    std::array<char, MAX_STRING_BYTES + 1> decoded{};
    std::size_t length = 0;
    check(!bad_string.get_string(decoded, length), "codec rejects overlong UTF-8");

    const Bytes nan = hex("7F C0 00 00");
    Reader nan_reader(nan.data(), nan.size());
    float value = 0.0F;
    check(!nan_reader.get_f32(value), "codec rejects NaN on decode");

    const Bytes runt = hex("00 00 00");
    Reader short_reader(runt.data(), runt.size());
    std::int32_t integer = 0;
    check(!short_reader.get_i32(integer), "codec rejects a truncated int32");
}

}  // namespace

int main() {
    test_v1_anchor();
    test_v2_primitives();
    test_v3_route_request();
    test_v4_route_reply();
    test_v5_callback();
    test_v6_monitor_end();
    test_v7_error_reply();
    test_codec_rejections();
    std::cout << "\nC++ conformance: " << checks << " checks, " << failures
              << " failures\n";
    return failures == 0 ? 0 : 1;
}
