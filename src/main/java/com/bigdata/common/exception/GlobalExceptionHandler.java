package com.bigdata.common.exception;

import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        logger.error("业务异常: {}", e.getMessage());
        int code = e.getCode();
        if (code == 403) {
            return Result.error(403, e.getMessage());
        }
        if (code == 401) {
            return Result.error(401, e.getMessage());
        }
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        String username = SecurityUtils.getCurrentUsername();
        String roles = SecurityUtils.getCurrentUserRoles().toString();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        logger.warn("访问被拒绝: 用户={}, 角色={}, 请求={} {}, 原因={}", username, roles, method, uri, e.getMessage());
        return Result.error(403, "无权访问该资源");
    }

    @ExceptionHandler(InsufficientAuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleInsufficientAuthenticationException(InsufficientAuthenticationException e) {
        logger.warn("认证不足: {}", e.getMessage());
        return Result.error(401, "未登录或登录已过期");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        logger.error("参数校验异常: {}", message);
        return Result.error(400, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数绑定失败";
        logger.error("参数绑定异常: {}", message);
        return Result.error(400, message);
    }

    /**
     * 请求参数类型不匹配（如 {@code storageEngine=nope} 无法转换为 {@code DatasourceType}）。
     * <p>
     * 该异常此前会落到下面的 {@code Exception} 兜底分支，被当成系统异常返回 500；
     * 实际属于调用方传参错误，应返回 400 并给出可读提示。
     * </p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        logger.warn("参数类型不匹配: name={}, value={}, requiredType={}",
                e.getName(), e.getValue(),
                e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : null);
        return Result.error(400, "参数 " + e.getName() + " 取值非法: " + e.getValue());
    }

    /**
     * 缺少必填请求参数（如 {@code GET /asset/table/{id}} 路径参数缺失对应的查询参数）。
     * <p>
     * 与上面的类型不匹配同理：此前会落到 {@code Exception} 兜底分支返回 500，
     * 前端据此提示「服务器内部错误,请联系管理员」，把调用方的参数漏传误导为服务故障。
     * 这里按 400 + 具体参数名返回，前端可据此直接定位问题。
     * </p>
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingRequestParameterException(MissingServletRequestParameterException e) {
        logger.warn("缺少必填请求参数: name={}", e.getParameterName());
        return Result.error(400, "缺少必填参数: " + e.getParameterName());
    }

    /**
     * 请求路径不存在 / 静态资源不存在。
     * <p>
     * 两者都会被下面的 {@code Exception} 兜底分支捕获并返回 500，把「客户端调错地址」
     * 误导为「服务端故障」（前端会提示「服务器内部错误,请联系管理员」）。
     * Spring 自身对这两种异常给出的语义就是 404，这里原样透出。
     * </p>
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFoundException(Exception e) {
        logger.warn("请求的资源不存在: {}", e.getMessage());
        return Result.error(404, "请求的资源不存在");
    }

    /** 请求方法不支持（如对只读接口发 POST）；同理会落到兜底分支变成 500，此处按 405 返回 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        logger.warn("请求方法不支持: method={}, supported={}", e.getMethod(), e.getSupportedHttpMethods());
        return Result.error(405, "请求方法不支持: " + e.getMethod());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        logger.error("系统异常", e);
        return Result.error("系统内部错误,请联系管理员");
    }
}
