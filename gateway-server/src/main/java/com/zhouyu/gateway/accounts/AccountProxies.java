package com.zhouyu.gateway.accounts;

import com.zhouyu.gateway.config.CredentialCipher;
import com.zhouyu.gateway.domain.AccountProxy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class AccountProxies {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final TransactionTemplate tx;

    public AccountProxies(JdbcTemplate jdbc, CredentialCipher cipher, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.tx = tx;
    }

    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS gateway_account_proxy (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(128) NOT NULL,
                    protocol VARCHAR(8) NOT NULL, host VARCHAR(255) NOT NULL, port INT NOT NULL,
                    username VARCHAR(128), credential VARBINARY(1024), import_key_hash CHAR(64),
                    UNIQUE KEY uk_proxy_import(import_key_hash)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    public record Input(String name, String protocol, String host, Integer port, String username, String password) {}
    public record Summary(long id, String name, String protocol, String host, int port, String username,
                          boolean hasPassword, int accounts) {}
    private record Stored(String username, byte[] cipher) {}

    public List<Summary> list() {
        return jdbc.query("""
                SELECT p.id,p.name,p.protocol,p.host,p.port,p.username,p.credential IS NOT NULL,
                    (SELECT COUNT(*) FROM gateway_upstream_account a WHERE a.proxy_id=p.id)
                FROM gateway_account_proxy p ORDER BY p.name,p.id
                """, (r, i) -> new Summary(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                r.getInt(5), r.getString(6), r.getBoolean(7), r.getInt(8)));
    }

    public long save(Long id, Input input) { return save(id, input, null); }

    long importProxy(String hash, Input input) {
        var existing = jdbc.queryForList("SELECT id FROM gateway_account_proxy WHERE import_key_hash=?", Long.class, hash);
        return existing.isEmpty() ? save(null, input, hash) : existing.get(0);
    }

    private long save(Long id, Input input, String hash) {
        String name = input.name() == null ? "" : input.name().strip();
        String protocol = input.protocol() == null ? "" : input.protocol().toLowerCase(Locale.ROOT);
        if (protocol.equals("socks5h")) protocol = "socks5";
        String host = input.host() == null ? "" : input.host().strip();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        String username = input.username() == null || input.username().isBlank() ? null : input.username();
        if (name.isEmpty() || name.length() > 128 || !Set.of("http", "socks5").contains(protocol)
                || !host.matches("[A-Za-z0-9.:-]{1,255}") || (host.contains(":") && !host.matches("[0-9a-fA-F:.]+"))
                || input.port() == null || input.port() < 1 || input.port() > 65535
                || (username != null && (username.length() > 128 || username.chars().anyMatch(Character::isISOControl)))
                || (input.password() != null && input.password().length() > 256)) {
            throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请填写有效的 HTTP 或 SOCKS5 代理地址和端口");
        }
        String address = host;
        String scheme = protocol;
        return tx.execute(ignored -> {
            long proxyId;
            byte[] previous = null;
            if (id == null) {
                jdbc.update("INSERT INTO gateway_account_proxy(name,protocol,host,port,username,import_key_hash) VALUES(?,?,?,?,?,?)",
                        name, scheme, address, input.port(), username, hash);
                proxyId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            } else {
                var stored = jdbc.query("SELECT username,credential FROM gateway_account_proxy WHERE id=? FOR UPDATE",
                        (r, i) -> new Stored(r.getString(1), r.getBytes(2)), id).stream().findFirst().orElseThrow(AccountProxies::missing);
                if (java.util.Objects.equals(username, stored.username())) previous = stored.cipher();
                proxyId = id;
            }
            byte[] credential = username == null ? null : input.password() == null || input.password().isEmpty()
                    ? previous : cipher.encrypt(input.password(), "account-proxy:" + proxyId);
            jdbc.update("UPDATE gateway_account_proxy SET name=?,protocol=?,host=?,port=?,username=?,credential=? WHERE id=?",
                    name, scheme, address, input.port(), username, credential, proxyId);
            return proxyId;
        });
    }

    public void bind(long accountId, long proxyId) {
        if (proxyId < 0) throw AccountPool.problem(HttpStatus.BAD_REQUEST, "请选择有效代理");
        if (proxyId > 0 && jdbc.queryForList("SELECT id FROM gateway_account_proxy WHERE id=? FOR UPDATE", Long.class, proxyId).isEmpty()) throw missing();
        jdbc.update("UPDATE gateway_upstream_account SET proxy_id=NULLIF(?,0) WHERE id=?", proxyId, accountId);
    }

    public AccountProxy connection(Long id) {
        if (id == null) return null;
        return jdbc.query("SELECT protocol,host,port,username,credential FROM gateway_account_proxy WHERE id=?",
                (r, i) -> new AccountProxy(r.getString(1), r.getString(2), r.getInt(3), r.getString(4),
                        r.getBytes(5) == null ? null : cipher.decrypt(r.getBytes(5), "account-proxy:" + id)), id)
                .stream().findFirst().orElseThrow(AccountProxies::missing);
    }

    public void delete(long id) {
        tx.executeWithoutResult(ignored -> {
            if (jdbc.queryForList("SELECT id FROM gateway_account_proxy WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) throw missing();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM gateway_upstream_account WHERE proxy_id=?", Integer.class, id) > 0) {
                throw AccountPool.problem(HttpStatus.CONFLICT, "请先解除账号绑定");
            }
            jdbc.update("DELETE FROM gateway_account_proxy WHERE id=?", id);
        });
    }

    public int test(long id) {
        var proxy = connection(id);
        try {
            return proxy.apply(HttpClient.newConnection()).get().uri("https://auth.openai.com/")
                    .responseSingle((res, bytes) -> bytes.thenReturn(res.status().code())).block(Duration.ofSeconds(12));
        } catch (RuntimeException error) {
            throw AccountPool.problem(HttpStatus.BAD_GATEWAY, "无法通过代理连接授权服务");
        }
    }

    private static RuntimeException missing() { return AccountPool.problem(HttpStatus.NOT_FOUND, "代理不存在"); }
}
