package app.framelink.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在解析摄影师上传文件之前鉴权，避免未登录请求占用上传临时空间 */
@Component
@Order(-90)
public class OwnerAuthenticationFilter extends OncePerRequestFilter {
    public static final String OWNER_ATTRIBUTE="framelink.authenticatedOwner";
    public static final String HEADER_ATTRIBUTE="framelink.authenticatedHeader";
    private final AuthService auth;
    private final ObjectMapper json;
    public OwnerAuthenticationFilter(AuthService auth,ObjectMapper json){this.auth=auth;this.json=json;}
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request){String path=request.getRequestURI();return !(path.equals("/api/projects")||path.startsWith("/api/projects/")||path.equals("/api/auth/me")||path.equals("/api/auth/logout"));}
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String header=request.getHeader("Authorization");
        try {String owner=auth.owner(header);request.setAttribute(OWNER_ATTRIBUTE,owner);request.setAttribute(HEADER_ATTRIBUTE,header);}
        catch(ApiProblem problem){response.setStatus(problem.status());response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),Map.of("message",problem.getMessage()));return;}
        chain.doFilter(request,response);
    }
}
