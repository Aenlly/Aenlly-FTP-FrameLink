package app.framelink.cloud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 项目资源、客户改选、不可变提交版本与成片交付 */
@Service
public class ProjectService {
    /** 所有者与客户共享的数据访问层 */
    private final Db db;
    private final AuthService auth;
    private final ObjectStore storage;
    private final ObjectMapper json;
    private final String publicUrl;
    public record Choice(String mediaId, boolean selected, String comment) { }
    public ProjectService(Db db, AuthService auth, ObjectStore storage, ObjectMapper json, @Value("${app.public-url}") String publicUrl) {
        this.db=db; this.auth=auth; this.storage=storage; this.json=json; this.publicUrl=publicUrl.replaceAll("/+$", "");
    }
    public List<Map<String,Object>> list(String owner) {
        return db.rows("SELECT p.*,(SELECT COUNT(*) FROM media m WHERE m.project_id=p.id AND m.kind=?) media_count FROM projects p WHERE owner_id=? ORDER BY updated_at DESC", MediaKind.PREVIEW.name(),owner);
    }
    public Map<String,Object> create(String owner, String name) {
        ApiProblem.require(name != null && !name.isBlank() && name.length()<=160,400,"项目名称应为1至160个字符");
        String id=id(); db.update("INSERT INTO projects(id,owner_id,name,state,round_no,selection_version,created_at,updated_at) VALUES(?,?,?,?,1,0,?,?)",id,owner,name.strip(),ProjectState.DRAFT.name(),now(),now());
        return details(owner,id);
    }
    public Map<String,Object> details(String owner, String id) {
        Map<String,Object> project=ownerProject(owner,id,false);
        project.put("media",media(id,Db.num(project,"roundNo")));
        project.put("shares",db.rows("SELECT id,expires_at,revoked,allow_download,created_at FROM shares WHERE project_id=? ORDER BY created_at DESC",id));
        List<Map<String,Object>> revisions=db.rows("SELECT * FROM selection_revisions WHERE project_id=? ORDER BY round_no DESC",id);
        revisions.forEach(r -> r.put("items",decode(Db.str(r,"itemsJson"))));
        revisions.forEach(r -> r.remove("itemsJson")); project.put("revisions",revisions); return project;
    }
    /** 工作台轻量刷新，只在项目版本变化时重新获取照片与选片详情 */
    public Map<String,Object> status(String owner,String id) {
        var project=ownerProject(owner,id,false);
        return Map.of("state",project.get("state"),"roundNo",project.get("roundNo"),"selectionVersion",project.get("selectionVersion"),"updatedAt",project.get("updatedAt"));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> upload(String owner,String projectId,MultipartFile file,String sourceKey,MediaKind kind,String parentId) throws Exception {
        Map<String,Object> project=ownerProject(owner,projectId,true);
        ApiProblem.require(!file.isEmpty(),400,"请选择文件");
        if(kind==MediaKind.PREVIEW) {
            ApiProblem.require(Set.of(ProjectState.DRAFT,ProjectState.SHARED).contains(state(project)),409,"本轮已提交，请先开放新一轮再调整预览照片");
            ApiProblem.require(file.getSize()<=ProductPolicy.PREVIEW_UPLOAD_LIMIT,413,"单张预览上传不能超过30MB");
        } else {
            ApiProblem.require(Set.of(ProjectState.CONFIRMED,ProjectState.DELIVERED).contains(state(project)),409,"请先确认客户选片，再上传交付文件");
            Map<String,Object> parent=db.one("SELECT id FROM media WHERE id=? AND project_id=? AND kind=?",parentId,projectId,MediaKind.PREVIEW.name());
            ApiProblem.require(confirmedItems(project).stream().anyMatch(c -> Db.str(c,"mediaId").equals(Db.str(parent,"id")) && Db.bool(c,"selected")),409,"该照片未在已确认选片中");
        }
        String originalHash; try(InputStream in=file.getInputStream()) { originalHash=digest(in); }
        String source=sourceKey==null||sourceKey.isBlank()?originalHash:sourceKey;
        ApiProblem.require(source.length()<=200,400,"来源标识过长");
        var existing=db.rows("SELECT * FROM media WHERE project_id=? AND source_key=? AND kind=?",projectId,source,kind.name());
        if(!existing.isEmpty()) { return existing.get(0); }
        byte[] preview=null; if(kind==MediaKind.PREVIEW) { int orientation=PreviewImage.orientation(file.getInputStream()); try(InputStream in=file.getInputStream()) { preview=PreviewImage.create(in,orientation); } }
        long length=preview==null?file.getSize():preview.length;
        int reserved=db.update("UPDATE accounts SET used_bytes=used_bytes+? WHERE id=? AND used_bytes+?<=quota_bytes",length,owner,length);
        ApiProblem.require(reserved==1,409,"云端空间不足，请清理文件或调整配额");
        String mediaId=id(),objectKey=owner+"/"+projectId+"/"+mediaId;
        String mime=kind==MediaKind.PREVIEW?"image/jpeg":deliveryMime(file.getOriginalFilename());
        String sha=preview==null?originalHash:HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preview));
        try(InputStream in=preview==null?file.getInputStream():new ByteArrayInputStream(preview)) { storage.put(objectKey,in,length,mime); }
        try {
            db.update("INSERT INTO media(id,project_id,source_key,name,kind,parent_id,object_key,mime,bytes,sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",mediaId,projectId,source,safeName(file.getOriginalFilename()),kind.name(),kind==MediaKind.DELIVERY?parentId:null,objectKey,mime,length,sha,now());
            db.update("UPDATE projects SET updated_at=?,selection_version=selection_version+1,state=? WHERE id=?",now(),kind==MediaKind.DELIVERY?ProjectState.DELIVERED.name():Db.str(project,"state"),projectId);
        } catch(RuntimeException failure) { storage.delete(objectKey); throw failure; }
        return db.one("SELECT * FROM media WHERE id=?",mediaId);
    }
    @Transactional(rollbackFor=Exception.class)
    public void deleteMedia(String owner,String projectId,String mediaId) throws IOException {
        Map<String,Object> project=ownerProject(owner,projectId,true);
        Map<String,Object> media=db.one("SELECT * FROM media WHERE project_id=? AND id=?",projectId,mediaId);
        ApiProblem.require(Set.of(ProjectState.DRAFT,ProjectState.SHARED).contains(state(project)),409,"本轮选片已经提交，不能移除关联文件");
        db.update("DELETE FROM selections WHERE project_id=? AND media_id=?",projectId,mediaId);
        db.update("DELETE FROM media WHERE project_id=? AND id=?",projectId,mediaId);
        db.update("UPDATE accounts SET used_bytes=used_bytes-? WHERE id=?",Db.num(media,"bytes"),owner);
        db.update("UPDATE projects SET selection_version=selection_version+1,updated_at=? WHERE id=?",now(),projectId);
        storage.delete(Db.str(media,"objectKey"));
    }
    @Transactional
    public Map<String,Object> share(String owner,String projectId,Integer days,boolean allowDownload) {
        Map<String,Object> project=ownerProject(owner,projectId,true);
        ApiProblem.require(!db.rows("SELECT id FROM media WHERE project_id=? AND kind=?",projectId,MediaKind.PREVIEW.name()).isEmpty(),409,"先加入预览照片，再生成客户链接");
        int expiry=days==null?ProductPolicy.SHARE_DAYS:days; validDays(expiry);
        String shareId=id(),token=auth.token(),code=auth.accessCode();
        db.update("UPDATE shares SET revoked=TRUE WHERE project_id=?",projectId);
        long expires=now()+Duration.ofDays(expiry).toMillis();
        db.update("INSERT INTO shares(id,project_id,token_hash,code_hash,expires_at,revoked,allow_download,created_at) VALUES(?,?,?,?,?,FALSE,?,?)",shareId,projectId,AuthService.hash(token),auth.encodeCode(code),expires,allowDownload,now());
        if(state(project)==ProjectState.DRAFT) { db.update("UPDATE projects SET state=?,updated_at=? WHERE id=?",ProjectState.SHARED.name(),now(),projectId); }
        return Map.of("id",shareId,"url",publicUrl+"/s/"+token,"token",token,"accessCode",code,"expiresAt",expires,"allowDownload",allowDownload);
    }
    public void updateShare(String owner,String projectId,String shareId,Integer days,Boolean allowDownload,boolean revoke) {
        ownerProject(owner,projectId,false); db.one("SELECT id FROM shares WHERE project_id=? AND id=?",projectId,shareId);
        if(revoke) { db.update("UPDATE shares SET revoked=TRUE WHERE id=?",shareId); return; }
        if(days!=null) { validDays(days); db.update("UPDATE shares SET expires_at=? WHERE id=?",now()+Duration.ofDays(days).toMillis(),shareId); }
        if(allowDownload!=null) { db.update("UPDATE shares SET allow_download=? WHERE id=?",allowDownload,shareId); }
    }
    public Map<String,Object> unlock(String shareToken,String code) {
        Map<String,Object> share=validShare(shareToken);
        ApiProblem.require(code!=null && auth.matchesCode(code,Db.str(share,"codeHash")),403,"访问码错误");
        String token=auth.token(); long expires=Math.min(Db.num(share,"expiresAt"),now()+ProductPolicy.SESSION_TTL);
        db.update("INSERT INTO visitor_grants(token_hash,share_id,expires_at) VALUES(?,?,?)",AuthService.hash(token),Db.str(share,"id"),expires);
        return Map.of("token",token,"expiresAt",expires);
    }
    public Map<String,Object> publicDetails(String shareToken,String visitorToken) {
        Map<String,Object> share=visitor(shareToken,visitorToken);
        Map<String,Object> project=db.one("SELECT id,name,state,round_no,selection_version,confirmed_revision,updated_at FROM projects WHERE id=?",Db.str(share,"projectId"));
        project.put("media",publicMedia(project,Db.bool(share,"allowDownload")));
        project.remove("confirmedRevision");
        project.put("allowDownload",Db.bool(share,"allowDownload")); project.put("expiresAt",Db.num(share,"expiresAt")); return project;
    }
    @Transactional
    public Map<String,Object> saveSelection(String shareToken,String visitorToken,int version,List<Choice> choices) {
        Map<String,Object> share=visitor(shareToken,visitorToken),project=project(Db.str(share,"projectId"),true);
        ApiProblem.require(state(project)==ProjectState.SHARED,409,"本轮已经提交或确认，请联系摄影师开放修改");
        ApiProblem.require(choices!=null && choices.size()<=1000,400,"单次保存数量超出范围");
        ApiProblem.require(db.update("UPDATE projects SET selection_version=selection_version+1,updated_at=? WHERE id=? AND selection_version=?",now(),Db.str(project,"id"),version)==1,409,"选片结果已有更新，请刷新后重新选择");
        for(Choice choice:choices) {
            ApiProblem.require(choice!=null && choice.mediaId()!=null && (choice.comment()==null||choice.comment().length()<=2000),400,"选片记录或留言无效");
            db.one("SELECT id FROM media WHERE id=? AND project_id=? AND kind=?",choice.mediaId(),Db.str(project,"id"),MediaKind.PREVIEW.name());
            db.update("DELETE FROM selections WHERE project_id=? AND round_no=? AND media_id=?",Db.str(project,"id"),Db.num(project,"roundNo"),choice.mediaId());
            db.update("INSERT INTO selections(project_id,round_no,media_id,selected,comment_text,updated_at) VALUES(?,?,?,?,?,?)",Db.str(project,"id"),Db.num(project,"roundNo"),choice.mediaId(),choice.selected(),choice.comment()==null?"":choice.comment(),now());
        }
        return publicDetails(shareToken,visitorToken);
    }
    @Transactional
    public Map<String,Object> submit(String shareToken,String visitorToken,int version) throws Exception {
        Map<String,Object> share=visitor(shareToken,visitorToken),project=project(Db.str(share,"projectId"),true);
        ApiProblem.require(state(project)==ProjectState.SHARED && Db.num(project,"selectionVersion")==version,409,"选片已更新或本轮已提交，请刷新后确认");
        List<Map<String,Object>> items=selectionSnapshot(Db.str(project,"id"),Db.num(project,"roundNo"));
        ApiProblem.require(items.stream().anyMatch(item -> Db.bool(item,"selected")),400,"请至少选择一张照片");
        String revision=id(); db.update("INSERT INTO selection_revisions(id,project_id,round_no,selection_version,items_json,submitted_at) VALUES(?,?,?,?,?,?)",revision,Db.str(project,"id"),Db.num(project,"roundNo"),version,json.writeValueAsString(items),now());
        db.update("UPDATE projects SET state=?,updated_at=? WHERE id=?",ProjectState.SUBMITTED.name(),now(),Db.str(project,"id"));
        return Map.of("revisionId",revision,"state",ProjectState.SUBMITTED.name());
    }
    @Transactional
    public Map<String,Object> confirm(String owner,String projectId,String revisionId) {
        Map<String,Object> project=ownerProject(owner,projectId,true);
        ApiProblem.require(state(project)==ProjectState.SUBMITTED,409,"本轮尚未提交或已确认");
        db.one("SELECT id FROM selection_revisions WHERE id=? AND project_id=? AND round_no=?",revisionId,projectId,Db.num(project,"roundNo"));
        db.update("UPDATE projects SET state=?,confirmed_revision=?,updated_at=? WHERE id=?",ProjectState.CONFIRMED.name(),revisionId,now(),projectId);
        return details(owner,projectId);
    }
    @Transactional
    public Map<String,Object> reopen(String owner,String projectId) {
        Map<String,Object> project=ownerProject(owner,projectId,true);
        ApiProblem.require(Set.of(ProjectState.SUBMITTED,ProjectState.CONFIRMED,ProjectState.DELIVERED).contains(state(project)),409,"当前轮次仍可修改");
        long round=Db.num(project,"roundNo");
        db.update("INSERT INTO selections(project_id,round_no,media_id,selected,comment_text,updated_at) SELECT project_id,?,media_id,selected,comment_text,? FROM selections WHERE project_id=? AND round_no=?",round+1,now(),projectId,round);
        db.update("UPDATE projects SET state=?,round_no=round_no+1,selection_version=0,confirmed_revision=NULL,updated_at=? WHERE id=?",ProjectState.SHARED.name(),now(),projectId);
        return details(owner,projectId);
    }
    public Map<String,Object> ownerMedia(String owner,String projectId,String mediaId) { ownerProject(owner,projectId,false); return db.one("SELECT * FROM media WHERE project_id=? AND id=?",projectId,mediaId); }
    public Map<String,Object> publicFile(String shareToken,String visitorToken,String mediaId,boolean download) {
        Map<String,Object> share=visitor(shareToken,visitorToken); String projectId=Db.str(share,"projectId");
        Map<String,Object> file=db.one("SELECT * FROM media WHERE project_id=? AND id=?",projectId,mediaId);
        if(download || MediaKind.DELIVERY.name().equals(Db.str(file,"kind"))) { ApiProblem.require(Db.bool(share,"allowDownload"),403,"摄影师尚未开放下载"); }
        if(MediaKind.DELIVERY.name().equals(Db.str(file,"kind"))) {
            Map<String,Object> project=project(projectId,false);
            ApiProblem.require(Set.of(ProjectState.CONFIRMED,ProjectState.DELIVERED).contains(state(project)),403,"本轮成片尚未开放交付");
            ApiProblem.require(confirmedItems(project).stream().anyMatch(c -> Db.str(c,"mediaId").equals(Db.str(file,"parentId")) && Db.bool(c,"selected")),403,"成片不在本轮确认范围");
        }
        return file;
    }
    public String csv(String owner,String projectId) {
        Map<String,Object> project=ownerProject(owner,projectId,false);
        StringBuilder csv=new StringBuilder("\uFEFF文件名,已选,客户留言\r\n");
        for(var item:selectionSnapshot(projectId,Db.num(project,"roundNo"))) { csv.append(csvCell(Db.str(item,"name"))).append(',').append(Db.bool(item,"selected")?"是":"否").append(',').append(csvCell(Db.str(item,"commentText"))).append("\r\n"); }
        return csv.toString();
    }
    private Map<String,Object> visitor(String token,String header) {
        Map<String,Object> share=validShare(token);
        ApiProblem.require(header!=null && header.startsWith("Bearer "),401,"请输入访问码");
        ApiProblem.require(!db.rows("SELECT token_hash FROM visitor_grants WHERE token_hash=? AND share_id=? AND expires_at>?",AuthService.hash(header.substring(7)),Db.str(share,"id"),now()).isEmpty(),401,"访问授权已失效，请重新输入访问码");
        return share;
    }
    private Map<String,Object> validShare(String token) {
        ApiProblem.require(token!=null && token.length()<=100,404,"分享链接无效");
        var rows=db.rows("SELECT * FROM shares WHERE token_hash=? AND revoked=FALSE AND expires_at>?",AuthService.hash(token),now());
        ApiProblem.require(!rows.isEmpty(),410,"链接已到期或已被摄影师撤销"); return rows.get(0);
    }
    private Map<String,Object> ownerProject(String owner,String id,boolean lock) { return db.one("SELECT * FROM projects WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),id,owner); }
    private Map<String,Object> project(String id,boolean lock) { return db.one("SELECT * FROM projects WHERE id=?"+(lock?" FOR UPDATE":""),id); }
    private List<Map<String,Object>> media(String id,long round) { return db.rows("SELECT m.*,COALESCE(s.selected,FALSE) selected,COALESCE(s.comment_text,'') comment_text FROM media m LEFT JOIN selections s ON s.media_id=m.id AND s.project_id=m.project_id AND s.round_no=? WHERE m.project_id=? ORDER BY m.created_at,m.id",round,id); }
    private List<Map<String,Object>> publicMedia(Map<String,Object> project,boolean downloads) {
        Set<String> parents=downloads && Set.of(ProjectState.CONFIRMED,ProjectState.DELIVERED).contains(state(project))
                ? confirmedItems(project).stream().filter(m -> Db.bool(m,"selected")).map(m -> Db.str(m,"mediaId")).collect(java.util.stream.Collectors.toSet()) : Set.of();
        return media(Db.str(project,"id"),Db.num(project,"roundNo")).stream().filter(m -> MediaKind.PREVIEW.name().equals(Db.str(m,"kind")) || parents.contains(Db.str(m,"parentId"))).map(m -> { Map<String,Object> row=new LinkedHashMap<>(m); row.keySet().retainAll(Set.of("id","name","kind","parentId","mime","bytes","selected","commentText","createdAt")); return row; }).toList();
    }
    private List<Map<String,Object>> selectionSnapshot(String id,long round) { return db.rows("SELECT m.id media_id,m.name,m.source_key,COALESCE(s.selected,FALSE) selected,COALESCE(s.comment_text,'') comment_text FROM media m LEFT JOIN selections s ON s.media_id=m.id AND s.project_id=m.project_id AND s.round_no=? WHERE m.project_id=? AND m.kind=? ORDER BY m.created_at,m.id",round,id,MediaKind.PREVIEW.name()); }
    private List<Map<String,Object>> confirmedItems(Map<String,Object> project) { if(Db.str(project,"confirmedRevision").isEmpty()) { return List.of(); } return decode(Db.str(db.one("SELECT items_json FROM selection_revisions WHERE id=? AND project_id=?",Db.str(project,"confirmedRevision"),Db.str(project,"id")),"itemsJson")); }
    private List<Map<String,Object>> decode(String raw) { try { return json.readValue(raw,new TypeReference<>(){}); } catch(Exception e) { throw new IllegalStateException("选片版本损坏",e); } }
    private ProjectState state(Map<String,Object> project) { return ProjectState.valueOf(Db.str(project,"state")); }
    private void validDays(int days) { ApiProblem.require(days>=1 && days<=ProductPolicy.MAX_SHARE_DAYS,400,"有效期应为1至90天"); }
    private String safeName(String raw) { String name=raw==null?"photo.jpg":raw.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]",""); return name.isBlank()?"photo.jpg":name.substring(0,Math.min(200,name.length())); }
    private String deliveryMime(String name) { String lower=name==null?"":name.toLowerCase(java.util.Locale.ROOT); return lower.endsWith(".jpg")||lower.endsWith(".jpeg")?"image/jpeg":lower.endsWith(".png")?"image/png":"application/octet-stream"; }
    private String digest(InputStream in) throws Exception { MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) { digest.update(buffer,0,n); } return HexFormat.of().formatHex(digest.digest()); }
    private String csvCell(String value) { if(value.matches("^[=+@\\-].*")) { value="'"+value; } return '"'+value.replace("\"","\"\"")+'"'; }
    private String id() { return UUID.randomUUID().toString(); }
    private long now() { return AuthService.now(); }
}
