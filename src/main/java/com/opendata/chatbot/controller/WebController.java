package com.opendata.chatbot.controller;

import com.opendata.chatbot.dao.User;
import com.opendata.chatbot.service.FirebaseStore;
import com.opendata.chatbot.service.LineService;
import com.opendata.chatbot.service.OpenDataCwb;
import com.opendata.chatbot.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
public class WebController {

    @Autowired
    private LineService lineServiceImpl;

    @Autowired
    private UserService userServiceImpl;

    @Autowired
    private OpenDataCwb openDataCwbImpl;

    @Autowired
    private FirebaseStore firebaseStoreImpl;

    /*
     * LineBot WebHook 驗證回訊息
     */
    @PostMapping("/webHook")
    public ResponseEntity<String> webHook(@RequestBody String requestBody,
                                          @RequestHeader("X-Line-Signature") String line_headers) {
        log.info("Begin Controller => {}", requestBody);
        return lineServiceImpl.WebHook(requestBody, line_headers);
    }

    @GetMapping(value = "/user", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<User> getAllUser() {
        return userServiceImpl.getAllUsers();
    }

    @PostMapping(value = "/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file/*, @RequestParam("test1") String test1, @RequestParam("test2") String test2,
                                          @RequestParam("test3") String test3, @RequestParam("test4") String test4*/) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
//        m.put("test1", test1);
//        m.put("test2", test2);
//        m.put("test3", test3);
//        m.put("test4", test4);
        m.put("imageUrl", firebaseStoreImpl.uploadFiles(file));
        log.info("json = {}", m);
        return m;
    }

    @PostMapping(value = "/download/{fileName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String downloadFile(@PathVariable String fileName) throws IOException {
        return firebaseStoreImpl.download(fileName);
    }
}
