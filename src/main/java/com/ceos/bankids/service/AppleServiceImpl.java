package com.ceos.bankids.service;

import com.ceos.bankids.constant.ErrorCode;
import com.ceos.bankids.controller.request.AppleRequest;
import com.ceos.bankids.dto.oauth.AppleHeaderDTO;
import com.ceos.bankids.dto.oauth.AppleKeyDTO;
import com.ceos.bankids.dto.oauth.AppleKeyListDTO;
import com.ceos.bankids.dto.oauth.AppleTokenDTO;
import com.ceos.bankids.exception.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = false)

public class AppleServiceImpl implements AppleService {

    private final WebClient webClient;

    @Value("${apple.team.id}")
    private String APPLE_TEAM_ID;
    @Value("${apple.client.id}")
    private String APPLE_CLIENT_ID;
    @Value("${apple.uri}")
    private String APPLE_URI;
    @Value("${apple.key}")
    private String APPLE_KEY;
    @Value("${apple.key.path}")
    private String APPLE_KEY_PATH;
    private String APPLE_SECRET;

    @Override
    public AppleKeyListDTO getAppleIdentityToken() {
        String getKeyURL = "https://appleid.apple.com/auth/keys";

        WebClient.ResponseSpec responseSpec = webClient.get().uri(getKeyURL).retrieve();

        try {
            AppleKeyListDTO appleKeyListDTO = responseSpec.bodyToMono(AppleKeyListDTO.class)
                .block();
            return appleKeyListDTO;
        } catch (Exception e) {
            e.printStackTrace();
            throw new BadRequestException(ErrorCode.APPLE_BAD_REQUEST.getErrorCode());
        }
    }

    @Override
    public Claims verifyIdentityToken(AppleRequest appleRequest, AppleKeyListDTO appleKeyListDTO) {
        PublicKey publicKey = null;
        try {
            SignedJWT signedJWT = SignedJWT.parse(appleRequest.getIdToken());
            JWTClaimsSet payload = signedJWT.getJWTClaimsSet();

            Date currentTime = new Date(System.currentTimeMillis());
            if (!currentTime.before(payload.getExpirationTime())) {
                throw new BadRequestException(ErrorCode.APPLE_TOKEN_EXPIRED.getErrorCode());
            }

            String headerOfIdentityToken = appleRequest.getIdToken()
                .substring(0, appleRequest.getIdToken().indexOf("."));

            AppleHeaderDTO appleHeaderDTO = new ObjectMapper().readValue(
                new String(Base64.getDecoder().decode(headerOfIdentityToken), "UTF-8"),
                AppleHeaderDTO.class);
            AppleKeyDTO appleKeyDTO = appleKeyListDTO.getMatchedKeyBy(
                    appleHeaderDTO.getKid(),
                    appleHeaderDTO.getAlg())
                .orElseThrow(
                    () -> new BadRequestException(ErrorCode.APPLE_KEY_UNAVAILABLE.getErrorCode()));

            byte[] nBytes = Base64.getUrlDecoder().decode(appleKeyDTO.getN());
            byte[] eBytes = Base64.getUrlDecoder().decode(appleKeyDTO.getE());

            BigInteger n = new BigInteger(1, nBytes);
            BigInteger e = new BigInteger(1, eBytes);

            RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(n, e);
            KeyFactory keyFactory = KeyFactory.getInstance(appleKeyDTO.getKty());
            publicKey = keyFactory.generatePublic(publicKeySpec);

        } catch (ParseException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException ex) {
            ex.printStackTrace();
        } catch (InvalidKeySpecException ex) {
            ex.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return Jwts.parser().setSigningKey(publicKey).parseClaimsJws(appleRequest.getIdToken())
            .getBody();
    }

    @Override
    public AppleTokenDTO getAppleAccessToken(AppleRequest appleRequest) {
        APPLE_SECRET = makeClientSecret();

        String getTokenURL =
            "https://appleid.apple.com/auth/token?client_id=" + APPLE_CLIENT_ID + "&client_secret="
                + APPLE_SECRET + "&grant_type=authorization_code&code=" + appleRequest.getCode()
                + "&redirect_uri=" + APPLE_URI;

        WebClient.ResponseSpec responseSpec = webClient.post().uri(getTokenURL).retrieve();

        try {
            AppleTokenDTO appleTokenDTO = responseSpec.bodyToMono(AppleTokenDTO.class).block();
            return appleTokenDTO;
        } catch (Exception e) {
            e.printStackTrace();
            throw new BadRequestException(ErrorCode.APPLE_BAD_REQUEST.getErrorCode());
        }
    }

    private String makeClientSecret() {
        Date expirationDate = Date.from(
            LocalDateTime.now().plusDays(30).atZone(ZoneId.systemDefault()).toInstant());
        return Jwts.builder()
            .setHeaderParam("kid", APPLE_KEY)
            .setHeaderParam("alg", "ES256")
            .setIssuer(APPLE_TEAM_ID)
            .setIssuedAt(new Date(System.currentTimeMillis()))
            .setExpiration(expirationDate)
            .setAudience("https://appleid.apple.com")
            .setSubject(APPLE_CLIENT_ID)
            .signWith(SignatureAlgorithm.ES256, getPrivateKey())
            .compact();
    }

    private PrivateKey getPrivateKey() {

        ClassPathResource resource = new ClassPathResource(APPLE_KEY_PATH);
        byte[] content = null;
        KeyFactory factory = null;
        PKCS8EncodedKeySpec priKeySpec = null;

        try (FileReader keyReader = new FileReader(resource.getFile());
            PemReader pemReader = new PemReader(keyReader)) {
            {
                factory = KeyFactory.getInstance("EC");

                PemObject pemObject = pemReader.readPemObject();
                content = pemObject.getContent();
                priKeySpec = new PKCS8EncodedKeySpec(content);
                return factory.generatePrivate(priKeySpec);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
        }

        return null;
    }
}
