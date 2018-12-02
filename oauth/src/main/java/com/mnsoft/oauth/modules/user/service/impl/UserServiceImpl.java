package com.mnsoft.oauth.modules.user.service.impl;

import com.mnsoft.oauth.model.Account;
import com.mnsoft.oauth.modules.user.service.UserService;
import com.mnsoft.oauth.modules.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Author by hiling, Email admin@mn-soft.com, Date on 11/29/2018.
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    UserMapper userMapper;

    @Value("${datasource.user.sql.login}")
    String loginSql;

    @Override
    public Account login(String username, String password) {

//        String sql = "select id as userId,username, password from user where username=#{username};";
        String sql = StringUtils.replace(loginSql, "{", "#{");
        log.info("login sql: " + sql);
        Account user = userMapper.login(sql, username, password);
        return user;
    }
}