cmake_minimum_required(VERSION 3.22.1)
project(airplaytv C CXX)

set(OPENSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/openssl/${ANDROID_ABI})
if(NOT EXISTS ${OPENSSL_DIR}/lib/libcrypto.a)
  message(FATAL_ERROR "OpenSSL for ${ANDROID_ABI} not found: run scripts/build-openssl-android.sh first")
endif()
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${OPENSSL_DIR}/lib/libcrypto.a)

# libplist 2.7 (C API only). The HAVE_* defines replace its autoconf config.h; bionic has all of them.
file(GLOB PLIST_SRC libplist/src/*.c libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC libplist/include PRIVATE libplist/src libplist/libcnary/include)
target_compile_definitions(plist PRIVATE
  HAVE_STRNDUP HAVE_STRPTIME HAVE_TIMEGM HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE
  HAVE_MEMMEM HAVE_GMTIME_R HAVE_LOCALTIME_R __LITTLE_ENDIAN__=1 PACKAGE_VERSION="2.7.0")
target_compile_options(plist PRIVATE -w)

# Apple's ALAC decoder.
add_library(alac STATIC
  alac/ALACDecoder.cpp alac/ALACBitUtilities.c alac/EndianPortable.c
  alac/ag_dec.c alac/dp_dec.c alac/matrix_dec.c)
target_include_directories(alac PUBLIC alac)
# Its endian detection only knows x86; ARM is little-endian too.
target_compile_definitions(alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_compile_options(alac PRIVATE -w)

# UxPlay's AirPlay protocol library (lib/ of UxPlay), with our DNS-SD backend.
file(GLOB UXPLAY_SRC uxplay/*.c uxplay/playfair/*.c uxplay/llhttp/*.c)
add_library(uxplay STATIC ${UXPLAY_SRC} dnssd_android.c)
target_include_directories(uxplay PUBLIC uxplay uxplay/playfair uxplay/llhttp ${OPENSSL_DIR}/include)
target_compile_definitions(uxplay PRIVATE NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L
  _REENTRANT _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64 _LINUX TARGET_POSIX)
target_compile_options(uxplay PRIVATE -Wno-unused-variable -Wno-unused-function -Wno-deprecated-declarations)
target_link_libraries(uxplay PUBLIC plist crypto)

add_library(airplaytv SHARED airplay_jni.cpp)
target_include_directories(airplaytv PRIVATE . )
target_link_libraries(airplaytv uxplay alac log)
