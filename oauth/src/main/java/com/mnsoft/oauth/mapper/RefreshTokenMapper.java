package com.mnsoft.oauth.mapper;

import com.mnsoft.oauth.model.RevokeToken;
import com.mnsoft.oauth.model.RefreshToken;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Author by hiling, Email admin@mn-soft.com, Date on 10/12/2018.
 */
public interface RefreshTokenMapper {

    RefreshToken getByRefreshToken(@Param("refreshToken") String refreshToken);

    List<String> getRevokeRefreshToken(List<RevokeToken> list);

    Long insert(RefreshToken refreshToken);

    int updateLastUsedTimeById(@Param("id")Long id, @Param("lastUsedTime")LocalDateTime lastUsedTime);

    void batchDeleteByRefreshToken(@Param("refreshTokenList") List<String> refreshTokenList);

    void deleteExpiredRefreshToken();
}
