package com.github.hiling.gateway.modules.oauth.service.impl;

import com.github.hiling.gateway.modules.oauth.constant.ErrorMessage;
import com.github.hiling.gateway.modules.oauth.constant.GrantType;
import com.github.hiling.gateway.modules.oauth.constant.RedisNamespaces;
import com.github.hiling.gateway.modules.oauth.mapper.AccessTokenMapper;
import com.github.hiling.gateway.modules.oauth.mapper.RefreshTokenMapper;
import com.github.hiling.gateway.modules.oauth.model.AccessToken;
import com.github.hiling.gateway.modules.oauth.model.Account;
import com.github.hiling.gateway.modules.oauth.model.RefreshToken;
import com.github.hiling.gateway.modules.oauth.model.RevokeToken;
import com.github.hiling.gateway.modules.oauth.modules.client.mapper.ClientMapper;
import com.github.hiling.gateway.modules.oauth.modules.client.model.Client;
import com.github.hiling.gateway.modules.oauth.task.RemoveAccessTokenThread;
import com.github.hiling.gateway.modules.oauth.task.RemoveRefreshTokenThread;
import com.github.hiling.common.utils.DateTimeUtils;
import com.github.hiling.oauth.JwtUtils;
import com.github.hiling.gateway.modules.oauth.service.AccessTokenService;
import com.github.hiling.common.exception.BusinessException;
import com.github.hiling.common.utils.UuidUtils;
import com.github.hiling.gateway.modules.oauth.service.AccountService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Author by hiling, Email admin@mn-soft.com, Date on 10/9/2018.
 */
@Service
public class AccessTokenServiceImpl implements AccessTokenService {

    @Resource
    private AccessTokenMapper accessTokenMapper;

    @Resource
    private RefreshTokenMapper refreshTokenMapper;

    @Resource
    private ClientMapper clientMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * accessToken过期时间（秒），绝对过期，默认2小时
     */
    @Value("${oauth.access-token.expiration:7200}")
    Integer accessTokenExpiration;

    /**
     * 滑动过期时间（秒），默认24小时
     */
    @Value("${oauth.refresh-token.sliding-expiration:86400}")
    Integer refreshTokenSlidingExpiration;

    /**
     * 绝对过期时间（秒） ,默认7天
     */
    @Value("${oauth.refresh-token.absolute-expiration:604800}")
    Integer refreshTokenAbsoluteExpiration;

    /**
     * Password模式时，clientSecret是否为必填项，默认false
     */
    @Value("${oauth.password.client-secret.required:false}")
    Boolean clientSecretRequiredForPassword;

    public String getJwtToken(String accessToken) {
        if (StringUtils.isNotEmpty(accessToken)) {
            String jwtToken = stringRedisTemplate.opsForValue().get(RedisNamespaces.ACCESS_TOKEN + accessToken);
            if (StringUtils.isNotEmpty(jwtToken)) {
                return jwtToken;
            }
        }
        //如果缓存中没有，说明该Token已过期或错误，然后直接返回null，避免大量查询DB导致DB压力过大
        return null;
    }

    public AccessToken createAccessToken(String accessIp, String clientId, String clientSecret, String grantType,
                                         String userName, String password, String refreshToken) {

        GrantType type = GrantType.typeOf(grantType);

        //验证clientId & clientSecret是否正确, Password模式时，clientSecret是可选的；
        Client client = clientMapper.getForVerify(clientId);

        if (client == null) {
            throw new BusinessException(ErrorMessage.TOKEN_CLIENT_ERROR);
        }

        //验证来访IP是否授权
        if (StringUtils.isNotEmpty(client.getIpWhitelist())) {
            if (!StringUtils.contains(client.getIpWhitelist(), accessIp)) {
                throw new BusinessException(ErrorMessage.TOKEN_IP_WHITELIST_ERROR);
            }
        }

        AccessToken token = new AccessToken();
        Long userId;
        LocalDateTime now = LocalDateTime.now();

        if (type == GrantType.PASSWORD) {

            if (clientSecretRequiredForPassword && !StringUtils.equals(client.getClientSecret(), clientSecret)) {
                throw new BusinessException(ErrorMessage.TOKEN_CLIENT_ERROR);
            }

            //password模式，需要验证用户名密码
            Account account = accountService.login(userName, password);
            if (account == null) {
                throw new BusinessException(ErrorMessage.TOKEN_USER_ERROR);
            }
            userId = account.getUserId();
            refreshToken = UuidUtils.getUUID();
        } else if (type == GrantType.CLIENT_CREDENTIALS) {
            if (!StringUtils.equals(client.getClientSecret(), clientSecret)) {
                throw new BusinessException(ErrorMessage.TOKEN_CLIENT_ERROR);
            }
            //客户端模式时，不需要userId
            userId = 0L;
            refreshToken = UuidUtils.getUUID();
        } else if (type == GrantType.REFRESH_TOKEN) {

            if (StringUtils.isEmpty(refreshToken)) {
                throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_REQUIRED);
            }

            //获取该refresh token的信息
            RefreshToken refreshTokenFromDB = refreshTokenMapper.getByRefreshToken(refreshToken);

            if (refreshTokenFromDB == null) {
                throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_ERROR);
            }

            if (!StringUtils.equals(refreshTokenFromDB.getClientId(), clientId)) {
                throw new BusinessException(ErrorMessage.TOKEN_REFRESH_CLIENT_ID_NOT_MATCH);
            }

            //判断refresh token是否过期
            //绝对过期
            if (refreshTokenAbsoluteExpiration > 0) {
                if (refreshTokenFromDB.getCreateTime().plusSeconds(refreshTokenAbsoluteExpiration).isBefore(now)) {
                    throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_EXPIRATION);
                }
            }

            //滑动过期
            if (refreshTokenSlidingExpiration > 0) {
                if (refreshTokenFromDB.getLastUsedTime().plusSeconds(refreshTokenSlidingExpiration).isBefore(now)) {
                    throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_EXPIRATION);
                }
            }
            //使用refresh token模式时，需更新oauth_refresh_token表中的last_used_time字段
            refreshTokenMapper.updateLastUsedTimeById(refreshTokenFromDB.getId(), now);

            userId = refreshTokenFromDB.getUserId();

        } else {
            throw new BusinessException(ErrorMessage.TOKEN_GRANT_TYPE_NOT_SUPPORTED);
        }

        //创建Access Token(单机器的UUID的TPS在10万级别，随机数产生依赖与unix的/dev/random文件，因此增加线程不能提高生成效率)
        String accessToken = UuidUtils.getUUID();

        //生成jwtToken
        String jwtToken = JwtUtils.createJavaWebToken(userId,
                userName, clientId, client.getScope(),
                DateTimeUtils.localDateTimeToDate(now.plusSeconds(accessTokenExpiration)),
                DateTimeUtils.localDateTimeToDate(now));

        token.setClientId(clientId);
        token.setUserId(userId);
        token.setAccessToken(accessToken);
        token.setJwtToken(jwtToken);
        token.setRefreshToken(refreshToken);
        token.setExpiresIn(accessTokenExpiration);
        token.setCreateTime(now);

        Long accessTokenId = accessTokenMapper.insert(token);
        if (accessTokenId > 0) {

            //password模式时，需添加refreshToken到oauth_refresh_token表
            if (type == GrantType.PASSWORD || type == GrantType.CLIENT_CREDENTIALS) {
                refreshTokenMapper.insert(
                        new RefreshToken()
                                .setClientId(token.getClientId())
                                .setUserId(token.getUserId())
                                .setRefreshToken(refreshToken)
                                .setExpiresIn(refreshTokenSlidingExpiration > 0 ? refreshTokenSlidingExpiration : refreshTokenAbsoluteExpiration)
                                .setCreateTime(now)
                                .setLastUsedTime(now));

                //添加该授权信息插入到过期队列，有清除线程清除当前时间前的授权信息
                RemoveRefreshTokenThread.addRefreshTokenToRevokeQueue(clientId, userId, now);
            }

            //缓存到Redis oat=OAuth2 Access Token / ort=OAuth2 Refresh Token
            stringRedisTemplate.opsForValue().set(RedisNamespaces.ACCESS_TOKEN + accessToken, jwtToken, accessTokenExpiration, TimeUnit.SECONDS);

            //添加该授权信息插入到过期队列，有清除线程清除当前时间前的授权信息
            RemoveAccessTokenThread.addAccessTokenToRevokeQueue(clientId, userId, now);

            return token;
        }
        return null;
    }

    public boolean deleteToken(String accessToken) {
        //移除缓存
        stringRedisTemplate.delete(RedisNamespaces.ACCESS_TOKEN + accessToken);

        //移除DB
        String refreshToken = accessTokenMapper.getRefreshToken(accessToken);
        if (StringUtils.isNotEmpty(refreshToken)) {
            accessTokenMapper.batchDeleteByAccessToken(Arrays.asList(accessToken));
            refreshTokenMapper.batchDeleteByRefreshToken(Arrays.asList(refreshToken));
        }
        return true;
    }

    public List<String> getRevokeAccessToken(List<RevokeToken> list) {
        if (list.isEmpty()) {
            return Arrays.asList();
        }
        return accessTokenMapper.getRevokeAccessToken(list);
    }

    public void deleteExpiredAccessToken() {
        accessTokenMapper.deleteExpiredAccessToken();
    }

    public void batchDeleteByAccessToken(List<String> accessTokenList) {
        if (accessTokenList.isEmpty()) {
            return;
        }
        accessTokenMapper.batchDeleteByAccessToken(accessTokenList);
    }
}