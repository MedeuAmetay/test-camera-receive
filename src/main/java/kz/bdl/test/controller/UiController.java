package kz.bdl.test.controller;

import kz.bdl.test.service.LiveEventHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
@RequiredArgsConstructor
public class UiController {

    private final LiveEventHub hub;

    @GetMapping("/")
    public String home() {
        return "ui/home";
    }

    @GetMapping("/ui")
    public String ui() {
        return "ui/index";
    }

    @GetMapping("/ui/stream")
    @ResponseBody
    public SseEmitter stream() {
        return hub.subscribe();
    }
}
