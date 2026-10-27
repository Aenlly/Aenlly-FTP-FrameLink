package app.framelink.cloud;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 摄影师与客户接口，所有资源访问均在服务端鉴权 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final AuthService auth;
    private final ProjectService projects;
    private final ObjectStore storage;
    public ApiController(AuthService auth,ProjectService projects,ObjectStore storage) { this.auth=auth; this.projects=projects; this.storage=storage; }
    public record Credentials(@NotBlank @Email String email,@NotBlank @Size(max=72) String password,String inviteCode) { }
    public record ProjectRequest(@NotBlank @Size(max=160) String name) { }
    public record ShareRequest(Integer days,Boolean allowDownload) { }
    public record CodeRequest(@NotBlank @Size(max=64) String accessCode) { }
    public record SelectionRequest(int version,List<ProjectService.Choice> items) { }
    public record VersionRequest(int version) { }
    public record ConfirmRequest(@NotBlank String revisionId) { }
    @GetMapping("/health") public Map<String,Object> health() { return Map.of("status","UP","service","framelink-cloud"); }
    @PostMapping("/auth/register") public Map<String,Object> register(@Valid @RequestBody Credentials input,HttpServletRequest request) { auth.limit("auth:"+request.getRemoteAddr()); return auth.register(input.email(),input.password(),input.inviteCode()==null?"":input.inviteCode()); }
    @PostMapping("/auth/login") public Map<String,Object> login(@Valid @RequestBody Credentials input,HttpServletRequest request) { auth.limit("auth:"+request.getRemoteAddr()); return auth.login(input.email(),input.password()); }
    @PostMapping("/auth/logout") public Map<String,Object> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String header) { auth.logout(header); return Map.of("ok",true); }
    @GetMapping("/auth/me") public Map<String,Object> me(@RequestHeader(HttpHeaders.AUTHORIZATION) String header) { return auth.me(auth.owner(header)); }
    @GetMapping("/projects") public Object list(@RequestHeader(HttpHeaders.AUTHORIZATION) String header) { return projects.list(auth.owner(header)); }
    @PostMapping("/projects") public Object create(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@Valid @RequestBody ProjectRequest input) { return projects.create(auth.owner(header),input.name()); }
    @GetMapping("/projects/{id}") public Object project(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id) { return projects.details(auth.owner(header),id); }
    @GetMapping("/projects/{id}/status") public Object projectStatus(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id) { return projects.status(auth.owner(header),id); }
    @PostMapping("/projects/{id}/media") public Object upload(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id,@RequestParam MultipartFile file,@RequestParam(required=false) String sourceKey,@RequestParam(defaultValue="PREVIEW") MediaKind kind,@RequestParam(required=false) String parentId) throws Exception { return projects.upload(auth.owner(header),id,file,sourceKey,kind,parentId); }
    @DeleteMapping("/projects/{id}/media/{mediaId}") public Object delete(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id,@PathVariable String mediaId) throws Exception { projects.deleteMedia(auth.owner(header),id,mediaId); return Map.of("ok",true); }
    @PostMapping("/projects/{id}/shares") public Object share(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id,@RequestBody ShareRequest input) { return projects.share(auth.owner(header),id,input.days(),Boolean.TRUE.equals(input.allowDownload())); }
    @PatchMapping("/projects/{id}/shares/{shareId}") public Object shareSettings(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id,@PathVariable String shareId,@RequestBody ShareRequest input) { projects.updateShare(auth.owner(header),id,shareId,input.days(),input.allowDownload(),false); return Map.of("ok",true); }
    @DeleteMapping("/projects/{id}/shares/{shareId}") public Object revoke(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id,@PathVariable String shareId) { projects.updateShare(auth.owner(header),id,shareId,null,null,true); return Map.of("ok",true); }
    @PostMapping("/projects/{id}/confirm") public Object confirm(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id,@Valid @RequestBody ConfirmRequest input) { return projects.confirm(auth.owner(header),id,input.revisionId()); }
    @PostMapping("/projects/{id}/reopen") public Object reopen(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id) { return projects.reopen(auth.owner(header),id); }
    @GetMapping("/projects/{id}/selection.csv") public Object csv(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id) { return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=selection.csv").header(HttpHeaders.CONTENT_TYPE,"text/csv;charset=UTF-8").body(projects.csv(auth.owner(header),id)); }
    @GetMapping("/projects/{id}/media/{mediaId}/content") public ResponseEntity<StreamingResponseBody> ownerFile(@RequestHeader(HttpHeaders.AUTHORIZATION) String header,@PathVariable String id,@PathVariable String mediaId,@RequestParam(defaultValue="false") boolean download,@RequestHeader(value=HttpHeaders.RANGE,required=false) String range) { return file(projects.ownerMedia(auth.owner(header),id,mediaId),download,range); }
    @PostMapping("/public/{token}/unlock") public Object unlock(@PathVariable String token,@Valid @RequestBody CodeRequest input,HttpServletRequest request) { auth.limit("share:"+request.getRemoteAddr()); return projects.unlock(token,input.accessCode()); }
    @GetMapping("/public/{token}/project") public Object publicProject(@PathVariable String token,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String grant) { return projects.publicDetails(token,grant); }
    @PutMapping("/public/{token}/selection") public Object selection(@PathVariable String token,@RequestHeader(HttpHeaders.AUTHORIZATION) String grant,@RequestBody SelectionRequest input) { return projects.saveSelection(token,grant,input.version(),input.items()); }
    @PostMapping("/public/{token}/submit") public Object submit(@PathVariable String token,@RequestHeader(HttpHeaders.AUTHORIZATION) String grant,@RequestBody VersionRequest input) throws Exception { return projects.submit(token,grant,input.version()); }
    @GetMapping("/public/{token}/media/{id}") public ResponseEntity<StreamingResponseBody> publicFile(@PathVariable String token,@PathVariable String id,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String grant,@RequestParam(defaultValue="false") boolean download,@RequestHeader(value=HttpHeaders.RANGE,required=false) String range) { return file(projects.publicFile(token,grant,id,download),download,range); }
    private ResponseEntity<StreamingResponseBody> file(Map<String,Object> media,boolean download,String range) {
        long length=Db.num(media,"bytes"),start=0,end=length-1;
        if(range!=null) {
            ApiProblem.require(range.matches("bytes=\\d*-\\d*"),416,"不支持此下载范围");
            String[] parts=range.substring(6).split("-",-1);
            try { if(parts[0].isEmpty()) { start=Math.max(0,length-Long.parseLong(parts[1])); } else { start=Long.parseLong(parts[0]); if(!parts[1].isEmpty()) { end=Math.min(end,Long.parseLong(parts[1])); } } }
            catch(NumberFormatException e) { throw new ApiProblem(416,"下载范围无效"); }
            ApiProblem.require(start>=0 && start<=end && start<length,416,"下载范围无效");
        }
        final long offset=start,count=end-start+1;
        StreamingResponseBody body=out -> { try(var in=storage.read(Db.str(media,"objectKey"))) { in.skipNBytes(offset); byte[] buffer=new byte[65536]; long remaining=count; while(remaining>0) { int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining)); if(n<0) { throw new java.io.EOFException("资源长度不符"); } out.write(buffer,0,n); remaining-=n; } } };
        var response=ResponseEntity.status(range==null?200:206).header(HttpHeaders.CONTENT_TYPE,Db.str(media,"mime")).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").header("Accept-Ranges","bytes").header(HttpHeaders.CONTENT_LENGTH,Long.toString(count)).header(HttpHeaders.CONTENT_DISPOSITION,(download?ContentDisposition.attachment():ContentDisposition.inline()).filename(Db.str(media,"name"),StandardCharsets.UTF_8).build().toString());
        if(range!=null) { response.header(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+end+"/"+length); }
        return response.body(body);
    }
}
