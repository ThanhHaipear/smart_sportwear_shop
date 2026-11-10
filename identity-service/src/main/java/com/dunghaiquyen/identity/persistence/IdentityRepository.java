package com.dunghaiquyen.identity.persistence;

import com.dunghaiquyen.identity.domain.IdentityModels.Address;
import com.dunghaiquyen.identity.domain.IdentityModels.Role;
import com.dunghaiquyen.identity.domain.IdentityModels.Status;
import com.dunghaiquyen.identity.domain.IdentityModels.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
    private final JdbcTemplate jdbc;
    public IdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<User> userByEmail(String email) { return first(jdbc.query("select u.*,case when exists(select 1 from user_identities i where i.user_id=u.id and i.provider='GOOGLE') then 'GOOGLE' else 'LOCAL' end provider from users u where email=?", this::user, email)); }
    public Optional<User> userById(UUID id) { return first(jdbc.query("select u.*,case when exists(select 1 from user_identities i where i.user_id=u.id and i.provider='GOOGLE') then 'GOOGLE' else 'LOCAL' end provider from users u where id=?", this::user, id)); }
    public Optional<User> userByGoogleSubject(String subject) { return first(jdbc.query("select u.*,'GOOGLE' provider from users u join user_identities i on i.user_id=u.id where i.provider='GOOGLE' and i.provider_subject=?", this::user, subject)); }

    public User createLocal(String fullName, String email, String phone, String passwordHash) {
        UUID id=UUID.randomUUID(); jdbc.update("insert into users(id,email,password_hash,full_name,phone) values(?,?,?,?,?)",id,email,passwordHash,fullName,phone); return userById(id).orElseThrow();
    }
    public User createGoogle(String fullName,String email,String subject) {
        UUID id=UUID.randomUUID(); jdbc.update("insert into users(id,email,full_name,last_login_at) values(?,?,?,?)",id,email,fullName,Timestamp.from(Instant.now()));
        jdbc.update("insert into user_identities(user_id,provider,provider_subject,provider_email,last_login_at) values(?,'GOOGLE',?,?,?)",id,subject,email,Timestamp.from(Instant.now())); return userById(id).orElseThrow();
    }
    public void touchLogin(UUID id) { jdbc.update("update users set last_login_at=now(),updated_at=now(),version=version+1 where id=?",id); }
    public User updateProfile(UUID id,String fullName,String phone) {
        jdbc.update("update users set full_name=coalesce(?,full_name),phone=coalesce(?,phone),updated_at=now(),version=version+1 where id=?",fullName,phone,id); return userById(id).orElseThrow();
    }
    public User updateRole(UUID id,Role role) { jdbc.update("update users set role_code=?,updated_at=now(),version=version+1 where id=?",role.name(),id); return userById(id).orElseThrow(()->new NotFound("User not found")); }
    public User updateStatus(UUID id,Status status) { jdbc.update("update users set status=?,updated_at=now(),version=version+1 where id=?",status.name(),id); if(status!=Status.ACTIVE) revokeAll(id,"ACCOUNT_STATUS_CHANGED"); return userById(id).orElseThrow(()->new NotFound("User not found")); }
    public void updatePassword(UUID id,String hash) { jdbc.update("update users set password_hash=?,updated_at=now(),version=version+1 where id=?",hash,id); }

    public void saveRefresh(UUID userId,String raw,Instant expiresAt) { jdbc.update("insert into refresh_tokens(user_id,token_hash,expires_at) values(?,?,?)",userId,hash(raw),Timestamp.from(expiresAt)); }
    public Optional<RefreshRow> refresh(String raw) { return first(jdbc.query("select id,user_id,expires_at,revoked_at from refresh_tokens where token_hash=?",(r,n)->new RefreshRow(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant(),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant()),hash(raw))); }
    public int claimRefresh(UUID id) { return jdbc.update("update refresh_tokens set revoked_at=now(),revocation_reason='ROTATED' where id=? and revoked_at is null",id); }
    public void revokeAll(UUID userId,String reason) { jdbc.update("update refresh_tokens set revoked_at=coalesce(revoked_at,now()),revocation_reason=coalesce(revocation_reason,?) where user_id=? and revoked_at is null",reason,userId); }
    public void logout(String raw,UUID userId) { jdbc.update("update refresh_tokens set revoked_at=now(),revocation_reason='LOGOUT' where token_hash=? and user_id=? and revoked_at is null",hash(raw),userId); }

    public void createReset(UUID userId,String raw,Instant expires) { jdbc.update("update password_reset_tokens set used_at=coalesce(used_at,now()) where user_id=? and used_at is null",userId); jdbc.update("insert into password_reset_tokens(user_id,token_hash,expires_at) values(?,?,?)",userId,hash(raw),Timestamp.from(expires)); }
    public Optional<ResetRow> reset(String raw) { return first(jdbc.query("select id,user_id,expires_at,used_at from password_reset_tokens where token_hash=?",(r,n)->new ResetRow(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant(),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant()),hash(raw))); }
    public int claimReset(UUID id) { return jdbc.update("update password_reset_tokens set used_at=now() where id=? and used_at is null and expires_at>now()",id); }

    public List<Address> addresses(UUID userId) { return jdbc.query("select * from addresses where user_id=? order by is_default desc,created_at desc",this::address,userId); }
    public Address createAddress(UUID userId,String receiver,String phone,String province,String district,String ward,String line,boolean makeDefault) {
        boolean selected = makeDefault||addresses(userId).isEmpty();
        if(selected) jdbc.update("update addresses set is_default=false where user_id=?",userId);
        UUID id=UUID.randomUUID(); jdbc.update("insert into addresses(id,user_id,receiver_name,phone,province_name,district_name,ward_name,address_line,is_default) values(?,?,?,?,?,?,?,?,?)",id,userId,receiver,phone,province,district,ward,line,selected); return address(userId,id);
    }
    public Address updateAddress(UUID userId,UUID id,String receiver,String phone,String province,String district,String ward,String line) {
        int changed=jdbc.update("update addresses set receiver_name=coalesce(?,receiver_name),phone=coalesce(?,phone),province_name=coalesce(?,province_name),district_name=coalesce(?,district_name),ward_name=coalesce(?,ward_name),address_line=coalesce(?,address_line),updated_at=now() where id=? and user_id=?",receiver,phone,province,district,ward,line,id,userId);
        if(changed==0) throw new NotFound("Address not found"); return address(userId,id);
    }
    public void deleteAddress(UUID userId,UUID id) { jdbc.update("delete from addresses where id=? and user_id=?",id,userId); }
    public Address setDefault(UUID userId,UUID id) { jdbc.update("update addresses set is_default=false where user_id=?",userId); if(jdbc.update("update addresses set is_default=true,updated_at=now() where id=? and user_id=?",id,userId)==0) throw new NotFound("Address not found"); return address(userId,id); }
    private Address address(UUID userId,UUID id) { return first(jdbc.query("select * from addresses where id=? and user_id=?",this::address,id,userId)).orElseThrow(()->new NotFound("Address not found")); }

    private User user(ResultSet r,int n)throws SQLException { return new User(r.getObject("id",UUID.class),r.getString("full_name"),r.getString("email"),r.getString("phone"),r.getString("password_hash"),Role.valueOf(r.getString("role_code")),Status.valueOf(r.getString("status")),r.getString("provider"),r.getLong("version")); }
    private Address address(ResultSet r,int n)throws SQLException { return new Address(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),r.getString("receiver_name"),r.getString("phone"),r.getString("province_name"),r.getString("district_name"),r.getString("ward_name"),r.getString("address_line"),r.getBoolean("is_default"),r.getTimestamp("created_at").toInstant()); }
    private static <T> Optional<T> first(List<T> rows){return rows.stream().findFirst();}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public record RefreshRow(UUID id,UUID userId,Instant expiresAt,Instant revokedAt){}
    public record ResetRow(UUID id,UUID userId,Instant expiresAt,Instant usedAt){}
    public static class NotFound extends RuntimeException { public NotFound(String message){super(message);} }
}
