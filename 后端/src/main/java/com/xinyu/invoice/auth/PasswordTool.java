package com.xinyu.invoice.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 密码算法：必须与旧版 Python 完全兼容
 *
 * 存储格式：pbkdf2_sha256$200000$盐(hex)$哈希(hex)
 * 算法：PBKDF2WithHmacSHA256，200000 轮，32 字节密钥，密码按 UTF-8 编码
 *
 * 换后端不能让老用户登不进来，所以这里不能改算法、不能改轮数。
 */
public final class PasswordTool {

    public static final int ROUNDS = 200_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordTool() {}

    /** 生成新的密码哈希 */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ROUNDS);
        return "pbkdf2_sha256$" + ROUNDS + "$" + HexFormat.of().formatHex(salt)
                + "$" + HexFormat.of().formatHex(dk);
    }

    /** 校验密码（常量时间比较） */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            return false;
        }
        try {
            int rounds = Integer.parseInt(parts[1]);
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] want = HexFormat.of().parseHex(parts[3]);
            byte[] got = pbkdf2(password, salt, rounds);
            return MessageDigest.isEqual(want, got);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int rounds) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码算法不可用", e);
        }
    }

    /** 会话令牌：32 字节随机数，base64url（无填充），与 Python 的 token_urlsafe 一致 */
    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 生成初始密码：去掉容易混淆的 0O1lI */
    public static String newPassword(int length) {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 密码强度：至少 8 位、首尾不能有空格、不能是常见弱口令 */
    public static String problem(String password) {
        if (password == null || password.length() < 8) {
            return "密码至少 8 位";
        }
        if (!password.equals(password.strip())) {
            return "密码首尾不能有空格";
        }
        String[] weak = {"12345678", "123456789", "password", "11111111",
                "88888888", "admin123", "qwertyui", "abc12345"};
        for (String w : weak) {
            if (w.equalsIgnoreCase(password)) {
                return "这个密码太常见了，换一个";
            }
        }
        return null;
    }

    /** 用户名：字母开头，3-20 位，只允许字母数字下划线 */
    public static String usernameProblem(String username) {
        if (username == null || username.isBlank()) {
            return "请填写用户名";
        }
        if (!username.matches("[A-Za-z][A-Za-z0-9_]{2,19}")) {
            return "用户名要用字母开头，3-20 位，只能有字母、数字、下划线";
        }
        return null;
    }
}
