package com.settleflow.payment.api;

/**
 * 웹훅 서명이 <b>어느 키로</b> 통과했나. 시크릿 교체 기간에 구 키를 언제 지워도 되는지 판단하는 근거다 —
 * {@code PREVIOUS} 로 통과하는 요청이 사라져야 PG 가 새 키로 다 넘어온 것이다.
 */
public enum VerifiedKey { CURRENT, PREVIOUS }
