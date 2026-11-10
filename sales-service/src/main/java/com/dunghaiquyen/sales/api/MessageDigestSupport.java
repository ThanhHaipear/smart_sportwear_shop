package com.dunghaiquyen.sales.api;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
final class MessageDigestSupport{private MessageDigestSupport(){}static boolean equals(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}}
