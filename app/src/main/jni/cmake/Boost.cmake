# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

set(BOOST_VERSION 1.89.0)
set(BOOST_SHA256
    67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74)

if(NOT EXISTS "boost-${BOOST_VERSION}.tar.xz")
  # GitHub release downloads fail now and then; retry before giving up.
  foreach(attempt RANGE 1 3)
    message(STATUS "Downloading Boost ${BOOST_VERSION} (attempt ${attempt}) ......")
    file(
      DOWNLOAD
      "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
      boost-${BOOST_VERSION}.tar.xz
      STATUS boost_download_status
      SHOW_PROGRESS)
    list(GET boost_download_status 0 boost_download_code)
    if(boost_download_code EQUAL 0)
      file(SHA256 boost-${BOOST_VERSION}.tar.xz boost_download_hash)
      if(boost_download_hash STREQUAL BOOST_SHA256)
        break()
      endif()
      set(boost_download_status "SHA-256 mismatch: ${boost_download_hash}")
    endif()
    file(REMOVE boost-${BOOST_VERSION}.tar.xz)
    if(attempt EQUAL 3)
      message(FATAL_ERROR "Failed to download Boost: ${boost_download_status}")
    endif()
    execute_process(COMMAND ${CMAKE_COMMAND} -E sleep 5)
  endforeach()

  message(STATUS "Remove older version Boost")
  file(REMOVE_RECURSE "${CMAKE_SOURCE_DIR}/boost")
endif()

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/boost")
  message(STATUS "Extracting Boost ${BOOST_VERSION} ......")
  file(ARCHIVE_EXTRACT INPUT boost-${BOOST_VERSION}.tar.xz DESTINATION
       ${CMAKE_SOURCE_DIR})
  file(RENAME "boost-${BOOST_VERSION}" boost)
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    range
    regex
    scope_exit
    signals2
    utility
    uuid)

add_subdirectory(boost EXCLUDE_FROM_ALL)
