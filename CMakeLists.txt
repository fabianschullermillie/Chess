cmake_minimum_required(VERSION 3.16)
project(Wombat)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
if (NOT EMSCRIPTEN AND NOT CMAKE_SYSTEM_PROCESSOR MATCHES "arm64|aarch64")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -march=native")
endif ()

find_package(Threads REQUIRED)

set(SURGE_SOURCES
        lib/surge/src/position.cpp
        lib/surge/src/tables.cpp
        lib/surge/src/types.cpp
)

add_library(fathom STATIC lib/Fathom/src/tbprobe.c)
set_target_properties(fathom PROPERTIES C_STANDARD 99 C_STANDARD_REQUIRED ON)
target_include_directories(fathom PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/lib/Fathom/src)
target_compile_options(fathom PRIVATE -O2 -w)

# Embed the NNUE network into the binary. Without nnue/wombat.nnue the engine uses the classical eval.
set(NNUE_FILE ${CMAKE_CURRENT_SOURCE_DIR}/nnue/wombat.nnue)
set(NNUE_SOURCE ${CMAKE_CURRENT_BINARY_DIR}/nnue_data.cpp)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${NNUE_FILE})
if (EXISTS ${NNUE_FILE})
    file(READ ${NNUE_FILE} NNUE_HEX HEX)
    string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," NNUE_BYTES "${NNUE_HEX}")
    file(SIZE ${NNUE_FILE} NNUE_SIZE)
else ()
    set(NNUE_BYTES "0")
    set(NNUE_SIZE 0)
endif ()
file(WRITE ${NNUE_SOURCE} "#include <cstddef>\nalignas(64) extern const unsigned char NNUE_EMBEDDED_DATA[] = {${NNUE_BYTES}};\nextern const size_t NNUE_EMBEDDED_SIZE = ${NNUE_SIZE};\n")

set(ENGINE_SOURCES
        ${NNUE_SOURCE}
        src/nnue.cpp
        src/board.cpp
        src/eval.cpp
        src/see.cpp
        src/tt.cpp
        src/timeman.cpp
        src/search.cpp
        src/uci.cpp
        src/datagen.cpp
        src/OpeningDB.cpp
        src/EndgameDB.cpp
        src/variant/variant.cpp
        src/variant/variants.cpp
        src/variant/position.cpp
        src/variant/movegen.cpp
        src/variant/eval.cpp
        ${SURGE_SOURCES}
)

if (EMSCRIPTEN)
    # WebAssembly build for the browser demo (web/). Lazy SMP runs on a pthread pool, which needs
    # SharedArrayBuffer, i.e. a cross-origin isolated page (see web/coi-serviceworker.js).
    add_executable(Wombat web/wasm_main.cpp ${ENGINE_SOURCES})
    target_compile_options(Wombat PRIVATE -pthread -msimd128)
    target_compile_options(fathom PRIVATE -pthread)
    target_link_options(Wombat PRIVATE
            -pthread
            -sENVIRONMENT=web,worker,node
            -sMODULARIZE -sEXPORT_NAME=createWombat
            -sEXPORTED_FUNCTIONS=_main,_wombat_command
            -sEXPORTED_RUNTIME_METHODS=ccall
            -sALLOW_MEMORY_GROWTH -sINITIAL_MEMORY=256MB -sMAXIMUM_MEMORY=2GB
            -sSTACK_SIZE=4MB -sDEFAULT_PTHREAD_STACK_SIZE=4MB
            # search thread + one helper per extra core, created per "go"
            "-sPTHREAD_POOL_SIZE=Math.min(globalThis.navigator?.hardwareConcurrency||4,32)+1"
            -sEXIT_RUNTIME=0)
    target_link_libraries(Wombat PRIVATE fathom)
    set_target_properties(Wombat PROPERTIES OUTPUT_NAME wombat)
    return()
endif ()

add_executable(Wombat main.cpp ${ENGINE_SOURCES})
target_link_libraries(Wombat PRIVATE fathom Threads::Threads)

# Move generator checks: the variant board with orthodox rules against surge, toroidal perft
add_executable(VariantTests tests/variant_tests.cpp ${ENGINE_SOURCES})
target_link_libraries(VariantTests PRIVATE fathom Threads::Threads)

add_executable(Openings util/create_openings.cpp ${SURGE_SOURCES})
# see src/surge_attacks.h
set_source_files_properties(util/create_openings.cpp PROPERTIES
        COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/src/surge_attacks.h")
