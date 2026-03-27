package com.example.paymentapi.controller;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public ModelAndView handleError(HttpServletRequest request) {
        ModelAndView modelAndView = new ModelAndView();
        modelAndView.setViewName("error");

        String errorMsg;
        int httpErrorCode = getErrorCode(request);

        errorMsg = switch (httpErrorCode) {
            case 400 -> "Http Error Code: 400. Bad Request";
            case 401 -> "Http Error Code: 401. Unauthorized";
            case 404 -> "Http Error Code: 404. Resource not found";
            case 500 -> "Http Error Code: 500. Internal Server Error";
            default -> "Unexpected error occurred";
        };

        modelAndView.addObject("errorMsg", errorMsg);
        return modelAndView;
    }

    private int getErrorCode(HttpServletRequest request) {
        Object code = request.getAttribute("jakarta.servlet.error.status_code");
        return code instanceof Integer ? (Integer) code : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
