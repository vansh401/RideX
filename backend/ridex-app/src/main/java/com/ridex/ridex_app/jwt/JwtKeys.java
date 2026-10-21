package com.ridex.ridex_app.jwt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import com.nimbusds.jose.jwk.RSAKey;

/**
 * The key pair that signs access tokens.
 *
 * RS256 (RSA + SHA-256) is ASYMMETRIC: tokens are signed with the PRIVATE key, which never leaves this application, and
 * verified with the PUBLIC key, which anyone may have. That is what lets other services trust our tokens without being
 * able to forge them. (HS256 used one shared secret: every service holding it could mint a token for any user.)
 *
 * In development the key is kept in a file (ridex.jwt.key-file) so tokens survive a restart. With no file configured, a
 * new key is made at every start (tests): old tokens stop working, which is harmless there.
 */
public record JwtKeys(RSAPrivateKey privateKey, RSAPublicKey publicKey, String keyId) {

    public static JwtKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return of((RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic());
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a signing key", e);
        }
    }

    /** Reads the key from the file, or creates and saves a new one if the file does not exist yet. */
    public static JwtKeys loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                String pem = Files.readString(file, StandardCharsets.UTF_8)
                        .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
                KeyFactory factory = KeyFactory.getInstance("RSA");
                RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
                // The public key is contained in a CRT private key: modulus and public exponent
                RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                        new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
                return of(privateKey, publicKey);
            }
            JwtKeys created = generate();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String body = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(created.privateKey().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(file, body, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));   // owner only (not on Windows)
            } catch (UnsupportedOperationException ignored) {
                // Windows: the folder's permissions apply
            }
            return created;
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not load or create the signing key " + file, e);
        }
    }

    private static JwtKeys of(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        try {
            // The key id is a fingerprint of the public key, so a key rotation produces a new id automatically
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            return new JwtKeys(privateKey, publicKey, HexFormat.of().formatHex(digest).substring(0, 16));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The private + public key as a JWK, for signing. */
    public RSAKey signingJwk() {
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
    }

    /** The public half only: what is published at /.well-known/jwks.json. */
    public RSAKey publicJwk() {
        return new RSAKey.Builder(publicKey).keyID(keyId).build();
    }

    /** The public key in PEM form, for services that prefer a file to a URL. */
    public String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }
}
