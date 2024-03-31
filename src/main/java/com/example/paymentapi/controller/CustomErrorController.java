package com.example.paymentapi.controller;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public ModelAndView handleError(HttpServletRequest request) {
        ModelAndView modelAndView = new ModelAndView();
        modelAndView.setViewName("error");

        String errorMsg = "";
        int httpErrorCode = getErrorCode(request);

        switch (httpErrorCode) {
            case 400: {
                errorMsg = "Http Error Code: 400. Bad Request";
                break;
            }
            case 401: {
                errorMsg = "Http Error Code: 401. Unauthorized";
                break;
            }
            case 404: {
                errorMsg = "Http Error Code: 404. Resource not found";
                break;
            }
            case 500: {
                errorMsg = "Http Error Code: 500. Internal Server Error";
                break;
            }
            default: {
                errorMsg = "Unexpected error occurred";
                break;
            }
        }

        modelAndView.addObject("errorMsg", errorMsg);
        return modelAndView;
    }

    private int getErrorCode(HttpServletRequest request) {
        return (Integer) request.getAttribute("javax.servlet.error.status_code");
    }

    public String getErrorPath() {
        return "/error";
    }
}