// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include <opencc/Common.hpp>
#include <opencc/DictConverter.hpp>
#include <opencc/Exception.hpp>
#include <opencc/SimpleConverter.hpp>
#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>

#include "jni-utils.h"

// OpenCC returns standard UTF-8. JNI's NewStringUTF accepts modified UTF-8, which encodes
// supplementary characters differently; decode explicitly before constructing a Java string.
static jstring NewJavaString(JNIEnv* env, const std::string& utf8) {
  std::vector<jchar> utf16;
  utf16.reserve(utf8.size());
  for (size_t i = 0; i < utf8.size();) {
    const auto first = static_cast<unsigned char>(utf8[i]);
    uint32_t codepoint = 0xfffd;
    size_t length = 1;
    uint32_t minimum = 0;
    if (first < 0x80) {
      codepoint = first;
    } else if ((first & 0xe0) == 0xc0) {
      codepoint = first & 0x1f;
      length = 2;
      minimum = 0x80;
    } else if ((first & 0xf0) == 0xe0) {
      codepoint = first & 0x0f;
      length = 3;
      minimum = 0x800;
    } else if ((first & 0xf8) == 0xf0) {
      codepoint = first & 0x07;
      length = 4;
      minimum = 0x10000;
    }
    bool valid = i + length <= utf8.size();
    for (size_t j = 1; valid && j < length; ++j) {
      const auto next = static_cast<unsigned char>(utf8[i + j]);
      valid = (next & 0xc0) == 0x80;
      if (valid) codepoint = (codepoint << 6) | (next & 0x3f);
    }
    valid = valid && (length == 1 || codepoint >= minimum) && codepoint <= 0x10ffff &&
        !(codepoint >= 0xd800 && codepoint <= 0xdfff);
    if (!valid) {
      codepoint = 0xfffd;
      length = 1;
    }
    i += length;
    if (codepoint <= 0xffff) {
      utf16.push_back(static_cast<jchar>(codepoint));
    } else {
      codepoint -= 0x10000;
      utf16.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
      utf16.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
    }
  }
  if (utf16.empty()) return env->NewStringUTF("");
  return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

// opencc

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_data_opencc_OpenCCDictManager_openCCLineConv(
    JNIEnv* env, jclass clazz, jstring input, jstring config_file_name) {
  try {
    opencc::SimpleConverter converter(CString(env, config_file_name));
    return NewJavaString(env, converter.Convert(*CString(env, input)));
  } catch (const opencc::Exception& e) {
    throwJavaException(env, e.what());
    return env->NewStringUTF("");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_data_opencc_OpenCCDictManager_openCCDictConv(
    JNIEnv* env, jclass clazz, jstring src, jstring dest, jboolean mode) {
  auto src_file = CString(env, src);
  auto dest_file = CString(env, dest);
  try {
    if (mode) {
      opencc::ConvertDictionary(src_file, dest_file, "ocd2", "text");
    } else {
      opencc::ConvertDictionary(src_file, dest_file, "text", "ocd2");
    }
  } catch (const opencc::Exception& e) {
    throwJavaException(env, e.what());
  }
}

// A converter kept across calls; building one loads every dictionary of its config.
extern "C" JNIEXPORT jlong JNICALL
Java_com_osfans_trime_data_opencc_OpenCCDictManager_openCCConverterOpen(
    JNIEnv* env, jclass clazz, jstring config_file_name) {
  try {
    return reinterpret_cast<jlong>(
        new opencc::SimpleConverter(CString(env, config_file_name)));
  } catch (const opencc::Exception& e) {
    throwJavaException(env, e.what());
    return 0;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_data_opencc_OpenCCDictManager_openCCConverterConvert(
    JNIEnv* env, jclass clazz, jlong converter, jstring input) {
  try {
    auto* c = reinterpret_cast<opencc::SimpleConverter*>(converter);
    return NewJavaString(env, c->Convert(*CString(env, input)));
  } catch (const opencc::Exception& e) {
    throwJavaException(env, e.what());
    return env->NewStringUTF("");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_data_opencc_OpenCCDictManager_openCCConverterClose(
    JNIEnv* env, jclass clazz, jlong converter) {
  delete reinterpret_cast<opencc::SimpleConverter*>(converter);
}
