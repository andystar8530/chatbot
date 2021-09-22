package com.opendata.chatbot.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opendata.chatbot.entity.*;
import com.opendata.chatbot.service.AesECB;
import com.opendata.chatbot.service.LineService;
import com.opendata.chatbot.service.OpenDataCwb;
import com.opendata.chatbot.util.HeadersUtil;
import com.opendata.chatbot.util.JsonConverter;
import com.opendata.chatbot.util.RestTemplateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Lookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class LineServiceImpl implements LineService {

    @Value("${spring.line.channelSecret}")
    private String channelSecret;

    @Value("${spring.line.channelToken}")
    private String channelToken;

    @Autowired
    private AesECB aesECBImpl;

    @Autowired
    private HeadersUtil headersUtil;

    @Autowired
    private EventWrapper eventWrapper;

    @Autowired
    private Event event;

    @Autowired
    private ReplyMessage replyMessage;

    @Autowired
    private OpenDataCwb openDataCwb;

    @Lookup
    private Messages getMessages() {
        return new Messages();
    }

    @Override
    public ResponseEntity<String> WebHook(String requestBody, String line_headers) {
        // 開執行緒去處理使用者訊息，先 return Line Http 200 訊息
        CompletableFuture.runAsync(() -> {
            // 驗證line傳過來的訊息
            if (validateLineHeader(requestBody, line_headers)) {
                replyMessage(requestBody);
            } else {
                new ResponseEntity<String>(HttpStatus.BAD_REQUEST);
            }
        });
        return new ResponseEntity<String>(HttpStatus.OK);
    }

    @Override
    public boolean validateLineHeader(String requestBody, String lineHeaders) {
        log.info("requestBody = {}", requestBody);
        log.info("lineHeaders = {}", lineHeaders);
        String secret = aesECBImpl.aesDecrypt(channelSecret);
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] source = requestBody.getBytes(StandardCharsets.UTF_8);
            String signature = Base64.encodeBase64String(mac.doFinal(source));
            if (signature.equals(lineHeaders)) {
                return true;
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            e.printStackTrace();
            log.error("validateLineHeader : {}", e.getMessage());
        }
        return false;
    }

    @Override
    public ResponseEntity<String> replyMessage(String requestBody) {
        eventWrapper = JsonConverter.toObject(requestBody, EventWrapper.class);
        log.trace("eventWrapper = {}", eventWrapper);
        String replyUrl = "https://api.line.me/v2/bot/message/reply";

        var replyToken = new AtomicReference<String>();

        // 取出User Event 的 資料，後續打API使用
        eventWrapper.getEvents().forEach(event -> {
            replyToken.set(event.getReplyToken());
            this.event = event;
        });
//        for (Event event : eventWrapper.getEvents()) {
//            replyToken = event.getReplyToken();
//            this.event = event;
//        }

        //送出參數
        var headers = headersUtil.setHeaders();
        var messagesList = new LinkedList<Messages>();

        log.trace("event = {}", event);
        if (event.getMessage().getType().equals("text")) {
            String openData = openDataCwb.weatherForecast(event.getMessage().getText());
            log.info("openData = {}", openData);

            Messages messages1 = getMessages();
            Messages messages2 = getMessages();

            if (openData != null) {
                var wList = JsonConverter.toArrayObject(openData, new TypeReference<LinkedList<WeatherForecast>>() {
                });
                messages1.setType("text");
                messages1.setText("天氣預報");

                messages2.setType("text");
                StringBuilder msg = new StringBuilder();
                assert wList != null;
                for (WeatherForecast wf : wList) {
                    switch (wf.getElementName()) {
                        case "PoP12h":
                        case "PoP6h":
                        case "RH":
                            msg.append(wf.getDescription()).append(" : ").append(wf.getValue()).append("%").append("\n");
                            break;
                        case "Wx":
                        case "CI":
                        case "WeatherDescription":
                        case "WS":
                        case "WD":
                            msg.append(wf.getDescription()).append(" : ").append(wf.getValue()).append("\n");
                            break;
                        case "AT":
                        case "T":
                        case "Td":
                            msg.append(wf.getDescription()).append(" : ").append(wf.getValue()).append("\u2103").append("\n");
                            break;
                    }
                }
                messages2.setText(msg.toString());

                messagesList.add(messages1);
                messagesList.add(messages2);
            } else {
                messages1.setType("text");
                messages1.setText("天氣預報地區 無此區域");
                messagesList.add(messages1);
            }
            replyMessage.setReplyToken(replyToken.get());
            replyMessage.setMessages(messagesList);

            return RestTemplateUtil.PostTemplate(replyUrl, JsonConverter.toJsonString(replyMessage), headers);
        } else if (event.getMessage().getType().equals("location")) {
            return RestTemplateUtil.PostTemplate(replyUrl, JsonConverter.toJsonString(replyMessage), headers);
        } else {
            return null;
        }
    }

    @Override
    public ResponseEntity<String> sendMessage(String json) {
        HttpHeaders headers = headersUtil.setHeaders();

        return null;
    }
}
