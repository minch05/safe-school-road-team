package com.safeschoolroad.route;

/** 화면의 초안이 사라지거나 다른 창의 초안으로 바뀐 경우. 저장하지 않고 재확인을 요청한다. */
public class DraftUnavailableException extends IllegalArgumentException {
    public DraftUnavailableException(String message) { super(message); }
}
