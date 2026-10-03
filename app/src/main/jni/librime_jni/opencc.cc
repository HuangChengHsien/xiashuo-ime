// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include <opencc/Common.hpp>
#include <opencc/DictConverter.hpp>
#include <opencc/Exception.hpp>
#include <opencc/SimpleConverter.hpp>
#include <string>

#include "jni-utils.h"

// opencc

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_data_opencc_OpenCCDictManager_openCCLineConv(
    JNIEnv* env, jclass clazz, jstring input, jstring config_file_name) {
  try {
    opencc::SimpleConverter converter(CString(env, config_file_name));
    return env->NewStringUTF(converter.Convert(*CString(env, input)).data());
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
    return env->NewStringUTF(c->Convert(*CString(env, input)).data());
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
