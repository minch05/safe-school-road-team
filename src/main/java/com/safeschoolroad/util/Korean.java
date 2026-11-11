package com.safeschoolroad.util;

// 한국어 조사 붙이기 (별명 뒤 "이/가")
public final class Korean {

    private Korean() {
    }

    /** "하늘" → "하늘이", "민지" → "민지가". 한글이 아니면 "이(가)" */
    public static String withSubject(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return word + "이(가)";
        }
        return word + ((last - 0xAC00) % 28 != 0 ? "이" : "가");
    }

    /** 부르는 말: "하늘" → "하늘아", "민지" → "민지야" */
    public static String withVocative(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return word;
        }
        return word + ((last - 0xAC00) % 28 != 0 ? "아" : "야");
    }
}
