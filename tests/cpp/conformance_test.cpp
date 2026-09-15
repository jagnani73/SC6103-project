#include "../../src/cpp/server/protocol.hpp"

#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>
#include <string>

namespace {

using namespace sc6103;
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

void test_anchor_vector() {
    const std::array<std::uint8_t, 60> expected{{
        0x00,0x00,0x07,0x8E, 0x00,0x00,0x00,0x09,
        0x53,0x69,0x6E,0x67,0x61,0x70,0x6F,0x72,0x65,0x00,0x00,0x00,
        0x00,0x00,0x00,0x06, 0x4C,0x6F,0x6E,0x64,0x6F,0x6E,0x00,0x00,
        0x00,0x00,0x07,0xEA, 0x00,0x00,0x00,0x0A,
        0x00,0x00,0x00,0x0E, 0x00,0x00,0x00,0x09,
        0x00,0x00,0x00,0x1E, 0x44,0x60,0xE0,0x00,
        0x00,0x00,0x00,0x78
    }};
    std::array<std::uint8_t, 60> actual{};
    Writer out(actual.data(), actual.size());
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
    check(out.ok(), "V1 anchor vector encodes without overflow");
    check(out.position() == expected.size(), "V2 anchor vector is exactly 60 bytes");
    check(actual == expected, "V3 anchor vector matches Java golden bytes");
}

void test_header_vector() {
    Header request;
    request.opcode = OP_RESERVE;
    request.semantics = SEM_AT_MOST_ONCE;
    request.client_id = 0xA1B2C3D4U;
    request.request_id = 17;
    request.ack_up_to = -1;
    request.payload_len = 8;
    std::array<std::uint8_t, HEADER_SIZE> bytes{};
    Writer out(bytes.data(), bytes.size());
    check(write_header(out, request), "V4 request header writes");
    check(bytes[0] == 0x53 && bytes[1] == 0x43 && bytes[2] == 0x36 && bytes[3] == 0x31,
          "V5 magic is big-endian SC61");
    check(bytes[20] == 0xA1 && bytes[21] == 0xB2 && bytes[22] == 0xC3 && bytes[23] == 0xD4,
          "V6 uint32 client id preserves top bit");
    check(bytes[28] == 0xFF && bytes[29] == 0xFF && bytes[30] == 0xFF && bytes[31] == 0xFF,
          "V7 NO_ACK is all ones");
    Reader in(bytes.data(), bytes.size());
    Header decoded;
    check(read_header(in, decoded), "V8 request header reads");
    check(decoded.client_id == request.client_id && decoded.request_id == 17
          && decoded.opcode == OP_RESERVE && decoded.payload_len == 8,
          "V9 request header round-trips");

    const Header callback = make_async(MSG_CALLBACK, 51, 23, SEM_AT_LEAST_ONCE, 16);
    check(callback.opcode == OP_NONE && callback.ack_up_to == NO_ACK
          && callback.request_id == 23 && callback.payload_len == 16,
          "V10 callback header sentinels match contract");
}

void test_primitives_and_bounds() {
    std::array<std::uint8_t, 128> bytes{};
    Writer out(bytes.data(), bytes.size());
    check(out.put_i32(std::numeric_limits<std::int32_t>::min()), "V11 int32 minimum writes");
    check(out.put_i32(std::numeric_limits<std::int32_t>::max()), "V12 int32 maximum writes");
    check(out.put_f32(-0.25F), "V13 negative float writes");
    check(!out.put_f32(std::numeric_limits<float>::infinity()), "V14 infinity is rejected");

    std::array<std::uint8_t, 8> padded{};
    Writer string_out(padded.data(), padded.size());
    check(string_out.put_string("abc", 3) && string_out.position() == 8,
          "V15 three-byte string receives one pad byte");
    check(padded[7] == 0, "V16 string padding is zero");
    Reader string_in(padded.data(), padded.size());
    std::array<char, MAX_STRING_BYTES + 1> decoded{};
    std::size_t length = 0;
    check(string_in.get_string(decoded, length) && length == 3
          && std::strcmp(decoded.data(), "abc") == 0 && string_in.remaining() == 0,
          "V17 padded string round-trips");

    const std::array<std::uint8_t, 8> malformed_utf8{{0,0,0,2,0xC0,0x80,0,0}};
    Reader bad_string(malformed_utf8.data(), malformed_utf8.size());
    check(!bad_string.get_string(decoded, length), "V18 overlong UTF-8 is rejected");

    std::array<std::uint8_t, 4> nan_bytes{{0x7F,0xC0,0x00,0x00}};
    Reader nan_reader(nan_bytes.data(), nan_bytes.size());
    float value = 0.0F;
    check(!nan_reader.get_f32(value), "V19 NaN is rejected on decode");

    std::array<std::uint8_t, 3> runt{{0,0,0}};
    Reader short_reader(runt.data(), runt.size());
    std::int32_t integer = 0;
    check(!short_reader.get_i32(integer), "V20 truncated int32 is rejected");
}

}  // namespace

int main() {
    test_anchor_vector();
    test_header_vector();
    test_primitives_and_bounds();
    std::cout << "\nC++ conformance: " << checks << " checks, " << failures
              << " failures\n";
    return failures == 0 ? 0 : 1;
}
