package com.opendata.chatbot.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opendata.chatbot.dao.User;
import com.opendata.chatbot.entity.*;
import com.opendata.chatbot.errorHandler.ErrorMessage;
import com.opendata.chatbot.repository.OpenDataRepo;
import com.opendata.chatbot.service.AesECB;
import com.opendata.chatbot.service.LineService;
import com.opendata.chatbot.service.OpenDataCwb;
import com.opendata.chatbot.service.UserService;
import com.opendata.chatbot.util.HeadersUtil;
import com.opendata.chatbot.util.JsonConverter;
import com.opendata.chatbot.util.RestTemplateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Lookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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

    @Value("${spring.line.replyUrl}")
    private String replyUrl;

    @Autowired
    private AesECB aesECBImpl;

    @Autowired
    private UserService userServiceImpl;

    @Autowired
    private HeadersUtil headersUtil;

    @Autowired
    private EventWrapper eventWrapper;

    @Autowired
    private Event event;

    @Autowired
    private OpenDataRepo openDataRepo;

    @Autowired
    private OpenDataCwb openDataCwbImpl;

    @Lookup
    private Messages getMessages() {
        return new Messages();
    }

    @Lookup
    private User getUser() {
        return new User();
    }

    @Override
    public ResponseEntity<String> WebHook(String requestBody, String line_headers) {
        // ?????????????????????????????????????????? return Line Http 200 ??????
        CompletableFuture.runAsync(() -> {
            // ??????line??????????????????
            if (validateLineHeader(requestBody, line_headers)) {
                log.info("????????????");
                replyMessage(requestBody);
            } else {
                throw new RuntimeException("validateLineHeader line_headers validate Error");
            }
        });
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Override
    public boolean validateLineHeader(String requestBody, String lineHeaders) {
        log.info("requestBody = {}", requestBody);
        log.info("lineHeaders = {}", lineHeaders);
        var secret = aesECBImpl.aesDecrypt(channelSecret);
        var key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] source = requestBody.getBytes(StandardCharsets.UTF_8);
            var signature = Base64.encodeBase64String(mac.doFinal(source));
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
    public void replyMessage(String requestBody) {
        // ????????? URL
        var url = new String(java.util.Base64.getDecoder().decode(replyUrl), StandardCharsets.UTF_8);
        //????????????
        var headers = headersUtil.setHeaders();
        var messagesList = new LinkedList<Messages>();
        eventWrapper = JsonConverter.toObject(requestBody, EventWrapper.class);
        log.trace("eventWrapper = {}", eventWrapper);

        var userId = new AtomicReference<String>();

        // ??????User Event ??? ??????????????????API??????
        this.event = null;
        eventWrapper.getEvents().forEach(event -> {
            userId.set(event.getSource().getUserId());
            this.event = event;
            log.info("event = {}", event);

            // ?????????????????? User ?????? DB
            CompletableFuture.runAsync(() -> {
                User user = getUser();
                if (userServiceImpl.getUserById(userId.get()) == null) {
                    user.setId(userId.get());
                    user.setCreateTime(LocalDateTime.now());
                    user.setType(event.getType());
                    userServiceImpl.saveUser(user);
                }
            });

            if (event.getMessage().getType().equals("text")) {
                replyWeatherForecast(event.getMessage().getText(), event.getReplyToken());
            } else if (event.getMessage().getType().equals("location")) {
                var address = event.getMessage().getAddress();
                var dist = address.substring(address.indexOf("???") + 1, address.indexOf("???") + 1);
                replyWeatherForecast(dist, event.getReplyToken());
            } else {
                var messages = getMessages();
                messages.setType("text");
                messages.setText("??????????????????");
                messagesList.add(messages);
                RestTemplateUtil.PostTemplate(url, JsonConverter.toJsonString(new ReplyMessage(event.getReplyToken(), messagesList)), headers);
            }
        });

    }

    @Override
    public ResponseEntity<String> replyWeatherForecast(String dist, String replyToken) {
        // ????????? URL
        var url = new String(java.util.Base64.getDecoder().decode(replyUrl), StandardCharsets.UTF_8);
        //????????????
        var headers = headersUtil.setHeaders();
        var messagesList = new LinkedList<Messages>();
        // ???????????? Data
        var openData = JsonConverter.toJsonString(openDataRepo.findByDistrict(dist).get().getWeatherForecast());

        var messages1 = getMessages();
        var messages2 = getMessages();
        if (null != openData) {
            var wList = JsonConverter.toArrayObject(openData, new TypeReference<LinkedList<WeatherForecast>>() {
            });
            messages1.setType("text");
            messages1.setText(dist + " ????????????");

            messages2.setType("text");
            var msg = new StringBuilder();
            assert wList != null;
            wList.forEach(wf -> {
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
            });
            messages2.setText(msg.toString());

            messagesList.add(messages1);
            messagesList.add(messages2);
        } else {
            messages1.setType("text");
            messages1.setText("?????????????????? ????????????");
            messagesList.add(messages1);
        }
        ReplyMessage replyMessage = new ReplyMessage(replyToken, messagesList);

        return RestTemplateUtil.PostTemplate(url, JsonConverter.toJsonString(replyMessage), headers);
    }

    @Override
    public ResponseEntity<String> pushMessage(String json) {
        var headers = headersUtil.setHeaders();

        return null;
    }
}
