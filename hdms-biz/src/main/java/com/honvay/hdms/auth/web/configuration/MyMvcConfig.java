package com.honvay.hdms.auth.web.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

/**
 * ========================
 *
 * @author bask
 * @Description: 配置文件读取
 * @date : 2020/10/20 18:30
 * Version: 1.0
 * ========================
 */
@Configuration
public class MyMvcConfig extends WebMvcConfigurerAdapter {

    @Value("${hdms.storage.location}")
    private String resourceLocalPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String os = System.getProperty("os.name");
        if(os.toLowerCase().startsWith("win")){
            registry.addResourceHandler("/file/**").addResourceLocations("file:"+resourceLocalPath+"/file/");
        }else{
            registry.addResourceHandler("/file/**").addResourceLocations(resourceLocalPath+"/file/");
        }
        super.addResourceHandlers(registry);
    }
}
