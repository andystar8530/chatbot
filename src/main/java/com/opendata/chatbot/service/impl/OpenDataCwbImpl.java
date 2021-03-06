package com.opendata.chatbot.service.impl;

import com.opendata.chatbot.dao.WeatherForecastDto;
import com.opendata.chatbot.entity.Center;
import com.opendata.chatbot.entity.Location;
import com.opendata.chatbot.entity.WeatherForecast;
import com.opendata.chatbot.repository.OpenDataRepo;
import com.opendata.chatbot.service.OpenDataCwb;
import com.opendata.chatbot.util.JsonConverter;
import com.opendata.chatbot.util.RestTemplateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Lookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class OpenDataCwbImpl implements OpenDataCwb {

    @Value("${spring.boot.openCWB.taipei}")
    private String taipeiUrl;

    @Value("${spring.boot.openCWB.newTaipei}")
    private String newTaipeiUrl;

    @Value("${spring.boot.openCWB.taoyuan}")
    private String taoyuanUrl;

    @Autowired
    private OpenDataRepo openDataRepo;

    @Lookup
    private Location getLocation() {
        return new Location();
    }

    @Lookup
    private WeatherForecast getWeatherForecast() {
        return new WeatherForecast();
    }

    @Override
    public String AllData(String url) {
        String body = null;
        try {
            body = RestTemplateUtil.GetNotValueTemplate(new String(Base64.getDecoder().decode(url), StandardCharsets.UTF_8)).getBody();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Base64 decode Error :{}", e.getMessage());
        }
        return body;
    }

    @Override
    public List<Location> taipeiCwb(String city) {
        var locationList = new LinkedList<Location>();

        Center center;
        String[] district;
        if (city.equals("?????????")) {
            center = JsonConverter.toObject(AllData(newTaipeiUrl), Center.class);
        } else if (city.equals("?????????")) {
            center = JsonConverter.toObject(AllData(taoyuanUrl), Center.class);
        } else {
            center = JsonConverter.toObject(AllData(taipeiUrl), Center.class);
        }
        // ?????????
        assert center != null;
        center.getRecords().getLocations().forEach(locations -> {
            locationList.addAll(locations.getLocation());
        });
        return locationList;
    }

    @Override
    public void weatherForecast(String city) {
        var weatherForecastList = new ArrayList<WeatherForecast>();
        var locationList = taipeiCwb(city);
        var district = new AtomicReference<String>(null);
        var n = new AtomicInteger();
        locationList.forEach(location -> {
            district.set(location.getLocationName());
            location.getWeatherElement().forEach(weatherElement -> {
                n.set(0);
                var weatherForecast = getWeatherForecast();
                weatherForecast.setDescription(weatherElement.getDescription());
                weatherForecast.setElementName(weatherElement.getElementName());
                weatherElement.getTime().forEach(time -> {
                    n.getAndIncrement();
                    if (n.get() < 2) {
                        weatherForecast.setStartTime(time.getStartTime());
                        weatherForecast.setDataTime(time.getDataTime());
                        time.getElementValue().forEach(elementValue -> {
                            // ?????? Wx ????????????
                            if (!elementValue.getMeasures().equals("????????? Wx ??????")) {
                                weatherForecast.setValue(elementValue.getValue());
                                weatherForecast.setMeasures(elementValue.getMeasures());
                            }
                        });
                    }
                });
                weatherForecastList.add(weatherForecast);
            });
            var weatherForecastDto = new WeatherForecastDto();
            var w = openDataRepo.findByDistrict(district.get());
            if (w.isPresent()) {
                weatherForecastDto.setId(w.get().getId());
            } else {
                weatherForecastDto.setId(UUID.randomUUID().toString());
            }
            weatherForecastDto.setDistrict(district.get());
            weatherForecastDto.setCreateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            // ???????????????
            weatherForecastDto.setWeatherForecast(weatherForecastList);
            openDataRepo.save(weatherForecastDto);
            // ??????
            weatherForecastList.clear();
        });
    }
}
