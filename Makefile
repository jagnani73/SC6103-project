CXX ?= c++
CXXFLAGS ?= -std=c++17 -Wall -Wextra -Wpedantic -O2
CPPFLAGS ?= -Isrc/cpp/server
BUILD_DIR := build/cpp
SERVER := $(BUILD_DIR)/flight_server
CONFORMANCE := $(BUILD_DIR)/cpp_conformance_test

ifeq ($(OS),Windows_NT)
SERVER := $(SERVER).exe
CONFORMANCE := $(CONFORMANCE).exe
LDFLAGS += -static
LDLIBS += -lws2_32
endif

.PHONY: all test clean

all: $(SERVER) $(CONFORMANCE)

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(SERVER): src/cpp/server/flight_server.cpp src/cpp/server/protocol.hpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $< -o $@ $(LDFLAGS) $(LDLIBS)

$(CONFORMANCE): tests/cpp/conformance_test.cpp src/cpp/server/protocol.hpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $< -o $@ $(LDFLAGS)

test: all
	$(CONFORMANCE)

clean:
	rm -rf $(BUILD_DIR)
