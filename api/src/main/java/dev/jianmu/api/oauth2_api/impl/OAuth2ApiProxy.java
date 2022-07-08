package dev.jianmu.api.oauth2_api.impl;

import dev.jianmu.api.oauth2_api.OAuth2Api;
import dev.jianmu.api.oauth2_api.enumeration.ThirdPartyTypeEnum;
import dev.jianmu.api.oauth2_api.exception.NotSupportedThirdPartPlatformException;
import dev.jianmu.api.oauth2_api.utils.ApplicationContextUtils;
import dev.jianmu.api.oauth2_api.vo.IRepoMemberVo;
import dev.jianmu.api.oauth2_api.vo.IRepoVo;
import dev.jianmu.api.oauth2_api.vo.IUserInfoVo;
import lombok.Builder;

import java.util.List;

/**
 * @author huangxi
 * @class OAuth2ApiProxy
 * @description OAuth2ApiProxy
 * @create 2021-06-30 14:08
 */
@Builder
public class OAuth2ApiProxy implements OAuth2Api {
    private ThirdPartyTypeEnum thirdPartyType;

    private OAuth2Api getApi() {
        switch (this.thirdPartyType) {
            case GITEE:
                return ApplicationContextUtils.getBean(GiteeApi.class);
            case GITLINK:
                return ApplicationContextUtils.getBean(GitlinkApi.class);
            default:
                throw new NotSupportedThirdPartPlatformException();
        }
    }

    @Override
    public String getAuthUrl(String redirectUri) {
        return this.getApi().getAuthUrl(redirectUri);
    }

    @Override
    public String getAccessToken(String code, String redirectUri) {
        return this.getApi().getAccessToken(code, redirectUri);
    }

    @Override
    public IUserInfoVo getUserInfo(String token) {
        return this.getApi().getUserInfo(token);
    }

    @Override
    public IRepoVo getRepo(String accessToken, String gitRepo, String gitRepoOwner) {
        return this.getApi().getRepo(accessToken, gitRepo, gitRepoOwner);
    }

    @Override
    public List<? extends IRepoMemberVo> getRepoMembers(String accessToken, String gitRepo, String gitRepoOwner) {
        return this.getApi().getRepoMembers(accessToken, gitRepo, gitRepoOwner);
    }

}
