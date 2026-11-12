package com.dunghaiquyen.identity.domain;

import com.dunghaiquyen.identity.config.IdentityProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetDelivery {
    private final JavaMailSender mail; private final IdentityProperties.PasswordReset properties;
    public PasswordResetDelivery(ObjectProvider<JavaMailSender> mailProvider,IdentityProperties.PasswordReset properties){this.mail=mailProvider.getIfAvailable();this.properties=properties;}
    public void send(String email,String rawToken){
        if(!properties.mailEnabled()) return;
        if(mail==null) throw new IllegalStateException("Password reset email is enabled but JavaMailSender is not configured");
        SimpleMailMessage message=new SimpleMailMessage(); message.setFrom(properties.from());message.setTo(email);message.setSubject("Reset your password");
        message.setText("Use this link within 30 minutes: "+properties.publicUrl()+"?token="+URLEncoder.encode(rawToken, StandardCharsets.UTF_8));mail.send(message);
    }
}
