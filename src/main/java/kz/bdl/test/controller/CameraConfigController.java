package kz.bdl.test.controller;

import kz.bdl.test.model.camera.CameraPushModels;
import kz.bdl.test.service.CameraConfigPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequiredArgsConstructor
public class CameraConfigController {

    private final CameraConfigPushService cameraConfigPushService;

    @GetMapping("/camera/config")
    public String cameraConfigPage() {
        return "ui/camera-config";
    }

    @GetMapping("/camera-arm")
    public String cameraArmPage() {
        return "ui/camera-arm";
    }

    @GetMapping("/camera/mixed-target")
    public String cameraMixedTargetPageAlias() {
        return "ui/camera-arm";
    }

    @PostMapping("/camera/config/api/push")
    @ResponseBody
    public CameraPushModels.Response pushToCameras(@RequestBody CameraPushModels.Request request) {
        return cameraConfigPushService.pushToCameras(request);
    }

    @PostMapping("/camera/config/api/push/mixed-target")
    @ResponseBody
    public CameraPushModels.Response pushMixedTarget(@RequestBody CameraPushModels.MixedTargetRequest request) {
        return cameraConfigPushService.pushMixedTargetDetection(request);
    }

    @PostMapping("/camera/mixed-target/api/push")
    @ResponseBody
    public CameraPushModels.Response pushMixedTargetSeparate(@RequestBody CameraPushModels.MixedTargetRequest request) {
        return cameraConfigPushService.pushMixedTargetDetection(request);
    }

    @PostMapping("/camera-arm/api/push")
    @ResponseBody
    public CameraPushModels.Response pushCameraArm(@RequestBody CameraPushModels.MixedTargetRequest request) {
        return cameraConfigPushService.pushMixedTargetDetection(request);
    }
}
