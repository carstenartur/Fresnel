package org.fresnel.backend.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DesktopModeIsolationTest {

    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    @Test
    void ordinaryServerContextDoesNotCreateDesktopBeans() {
        assertTrue(context.getBeansOfType(DesktopOpenQueue.class).isEmpty());
        assertTrue(context.getBeansOfType(DesktopOpenController.class).isEmpty());
    }

    @Test
    void ordinaryServerContextDoesNotRegisterDesktopHandlers() {
        assertTrue(handlerMapping.getHandlerMethods().values().stream()
                .map(HandlerMethod::getBeanType)
                .noneMatch(DesktopOpenController.class::equals));
    }
}
