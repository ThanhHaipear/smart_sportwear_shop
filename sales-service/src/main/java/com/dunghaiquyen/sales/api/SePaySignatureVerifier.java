package com.dunghaiquyen.sales.api;

import com.dunghaiquyen.sales.config.SalesProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SePaySignatureVerifier {
    private final SalesProperties properties; private final Clock clock;
    public SePaySignatureVerifier(SalesProperties properties){this(properties,Clock.systemUTC());}
    SePaySignatureVerifier(SalesProperties properties,Clock clock){this.properties=properties;this.clock=clock;}
    public void verify(String signature,String timestamp,byte[] rawBody){
        long value;try{value=Long.parseLong(timestamp);}catch(Exception e){throw new SecurityException("Invalid SePay webhook timestamp");}
        long now=clock.instant().getEpochSecond(), tolerance=properties.sepay().timestampToleranceSeconds();
        if(value<now-tolerance||value>now+tolerance)throw new SecurityException("Expired SePay webhook timestamp");
        if(signature==null||!signature.startsWith("sha256=")||signature.length()!=71)throw new SecurityException("Invalid SePay webhook signature");
        byte[] supplied;try{supplied=HexFormat.of().parseHex(signature.substring(7));}catch(Exception e){throw new SecurityException("Invalid SePay webhook signature");}
        if(!MessageDigest.isEqual(sign(timestamp,rawBody),supplied))throw new SecurityException("Invalid SePay webhook signature");
    }
    byte[] sign(String timestamp,byte[] body){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(properties.sepay().webhookSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));m.update(timestamp.getBytes(StandardCharsets.UTF_8));m.update((byte)'.');return m.doFinal(body);}catch(Exception e){throw new IllegalStateException(e);}}
}
