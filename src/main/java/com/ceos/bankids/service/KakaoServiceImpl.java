package com.ceos.bankids.service;

import com.ceos.bankids.controller.request.KakaoRequest;
import com.ceos.bankids.domain.User;
import com.ceos.bankids.dto.LoginDTO;
import com.ceos.bankids.dto.oauth.KakaoTokenDTO;
import com.ceos.bankids.dto.oauth.KakaoUserDTO;
import com.ceos.bankids.exception.BadRequestException;
import com.ceos.bankids.repository.UserRepository;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = false)
public class KakaoServiceImpl implements KakaoService {

    private final UserRepository uRepo;
    private final WebClient webClient;
    private final UserServiceImpl userService;

    @Value("${kakao.key}")
    private String KAKAO_KEY;
    @Value("${kakao.uri}")
    private String KAKAO_URI;

    @Override
    @Transactional
    public KakaoTokenDTO getKakaoAccessToken(KakaoRequest kakaoRequest) {
        String getTokenURL =
            "https://kauth.kakao.com/oauth/token?grant_type=authorization_code&client_id="
                + KAKAO_KEY + "&redirect_uri=" + KAKAO_URI + "&code="
                + kakaoRequest.getCode();

        return (KakaoTokenDTO) webClient.post().uri(getTokenURL).retrieve()
            .onStatus(HttpStatus::is4xxClientError,
                clientResponse -> Mono.error(new BadRequestException("잘못된 요청입니다.")))
            .bodyToMono(
                ParameterizedTypeReference.forType(KakaoTokenDTO.class))
            .block();
    }

    @Override
    @Transactional
    public KakaoUserDTO getKakaoUserCode(KakaoTokenDTO kakaoTokenDTO) {
        String getUserURL = "https://kapi.kakao.com/v2/user/me";

        return (KakaoUserDTO) webClient.post().uri(getUserURL)
            .header("Authorization", "Bearer " + kakaoTokenDTO.getAccessToken())
            .retrieve()
            .onStatus(HttpStatus::is4xxClientError,
                clientResponse -> Mono.error(new BadRequestException("잘못된 요청입니다.")))
            .bodyToMono(
                ParameterizedTypeReference.forType(KakaoUserDTO.class))
            .block();
    }

    @Override
    @Transactional
    public LoginDTO loginWithAuthenticationCode(KakaoUserDTO kakaoUserDTO,
        HttpServletResponse response) {
        Optional<User> user = uRepo.findByAuthenticationCode(kakaoUserDTO.getAuthenticationCode());
        if (user.isPresent()) {
            String accessToken = userService.issueNewTokens(user.get(), null, response);

            LoginDTO loginDTO = new LoginDTO(true, user.get().getIsKid(), accessToken);
            return loginDTO;
        } else {
            User newUser = User.builder()
                .username(kakaoUserDTO.getKakaoAccount().getProfile().getNickname())
                .authenticationCode(kakaoUserDTO.getAuthenticationCode())
                .provider("kakao").refreshToken("")
                .build();
            uRepo.save(newUser);

            String accessToken = userService.issueNewTokens(newUser, null, response);

            LoginDTO loginDTO = new LoginDTO(false, null, accessToken);
            return loginDTO;
        }
    }
}
