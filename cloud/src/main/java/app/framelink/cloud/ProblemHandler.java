package app.framelink.cloud;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 统一业务错误，保留服务端诊断日志 */
@RestControllerAdvice
public class ProblemHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ProblemHandler.class);
    @ExceptionHandler(ApiProblem.class) public ResponseEntity<?> business(ApiProblem problem) { return ResponseEntity.status(problem.status()).body(Map.of("message",problem.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<?> invalid(MethodArgumentNotValidException problem) { return ResponseEntity.badRequest().body(Map.of("message","请检查输入内容")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException problem) { return ResponseEntity.status(413).body(Map.of("message","文件超出上传大小限制")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception problem) { LOG.error("请求处理失败",problem); return ResponseEntity.internalServerError().body(Map.of("message","服务暂时无法完成操作，请稍后重试")); }
}
