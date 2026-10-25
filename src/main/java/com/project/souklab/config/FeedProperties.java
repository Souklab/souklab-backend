package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "app.feed")
@Data
public class FeedProperties {

    private int maxMediaPerPost;
    private List<String> allowedImageMimeTypes;
    private int maxTitleLength;
    private int maxBodyLength;
    private int maxCommentLength;
    private int maxTagsPerPost;
    private int maxTagLength;
}
