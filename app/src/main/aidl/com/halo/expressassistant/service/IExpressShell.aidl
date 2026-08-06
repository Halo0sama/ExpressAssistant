package com.halo.expressassistant.service;

interface IExpressShell {
    String probeAuth();
    String probePass();
    String mintToken(String appName, String deviceId);
    String testToken(String token, String cUserId, String infoJson);
    String testWebToken(String infoJson, String oaid, String vaid);
    String saveWebLogin(String oaid, String vaid);
    String getExpressList(String infoJson);
    String getExpressDetail(String infoJson);
    void destroy();
    void exit();
}
