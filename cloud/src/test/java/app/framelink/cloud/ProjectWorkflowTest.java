package app.framelink.cloud;

import static org.assertj.core.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"app.invite-code=test-invite", "spring.datasource.url=jdbc:h2:mem:workflow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
class ProjectWorkflowTest {
    @Autowired AuthService auth;
    @Autowired ProjectService projects;
    @Autowired Db db;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) { properties.add("app.storage-dir",()->System.getProperty("java.io.tmpdir")+"/framelink-test-"+ProcessHandle.current().pid()); }
    record Fixture(String owner,String project,String media,String share,String grant) { }
    String account() { var session=auth.register(UUID.randomUUID()+"@example.test","test-password-123","test-invite"); return auth.owner("Bearer "+session.get("token")); }
    MockMultipartFile image(String name) throws Exception { BufferedImage image=new BufferedImage(12,8,BufferedImage.TYPE_INT_RGB); var g=image.createGraphics(); g.setColor(Color.ORANGE); g.fillRect(0,0,12,8); g.dispose(); var out=new ByteArrayOutputStream(); ImageIO.write(image,"png",out); return new MockMultipartFile("file",name,"image/png",out.toByteArray()); }
    Fixture fixture() throws Exception { String owner=account(),project=Db.str(projects.create(owner,"周末人像"),"id"); String media=Db.str(projects.upload(owner,project,image("portrait.png"),"camera:card:1",MediaKind.PREVIEW,null),"id"); var share=projects.share(owner,project,7,false); String token=Db.str(share,"token"); return new Fixture(owner,project,media,token,"Bearer "+projects.unlock(token,Db.str(share,"accessCode")).get("token")); }
    ApiProblem fail(Runnable action,int expected) { ApiProblem problem=catchThrowableOfType(action::run,ApiProblem.class); assertThat(problem).isNotNull(); assertThat(problem.status()).isEqualTo(expected); return problem; }
    int version(Fixture f) { return (int)Db.num(projects.publicDetails(f.share,f.grant),"selectionVersion"); }
    @Test void ownersAndVisitorTokensCannotCrossProjects() throws Exception { var f=fixture(); String other=account(); fail(()->projects.details(other,f.project),404); fail(()->projects.ownerMedia(other,f.project,f.media),404); fail(()->projects.publicDetails(f.share,null),401); assertThat(projects.publicDetails(f.share,f.grant)).doesNotContainKeys("ownerId"); var media=(List<Map<String,Object>>)projects.publicDetails(f.share,f.grant).get("media"); assertThat(media.get(0)).doesNotContainKeys("objectKey","sha256","sourceKey"); }
    @Test void staleSaveRollsBackAndConfirmedRoundIsImmutable() throws Exception {
        var f=fixture(); int initial=version(f);
        projects.saveSelection(f.share,f.grant,initial,List.of(new ProjectService.Choice(f.media,true,"保留这张")));
        fail(()->projects.saveSelection(f.share,f.grant,initial,List.of(new ProjectService.Choice(f.media,false,""))),409);
        var submission=projects.submit(f.share,f.grant,initial+1); String revision=Db.str(submission,"revisionId");
        projects.confirm(f.owner,f.project,revision);
        fail(()->projects.saveSelection(f.share,f.grant,initial+1,List.of(new ProjectService.Choice(f.media,false,""))),409);
        projects.reopen(f.owner,f.project); assertThat(version(f)).isZero();
        projects.saveSelection(f.share,f.grant,0,List.of(new ProjectService.Choice(f.media,false,"第二轮调整")));
        var history=db.one("SELECT items_json FROM selection_revisions WHERE id=?",revision);
        assertThat(Db.str(history,"itemsJson")).contains("保留这张").contains("true").doesNotContain("第二轮调整");
    }
    @Test void revokedAndExpiredLinksInvalidateExistingGrants() throws Exception {
        var f=fixture(); var share=db.one("SELECT id FROM shares WHERE project_id=?",f.project);
        projects.updateShare(f.owner,f.project,Db.str(share,"id"),null,null,true);
        fail(()->projects.publicDetails(f.share,f.grant),410); fail(()->projects.publicFile(f.share,f.grant,f.media,false),410);
        var expired=fixture(); db.update("UPDATE shares SET expires_at=0 WHERE project_id=?",expired.project);
        fail(()->projects.publicDetails(expired.share,expired.grant),410);
    }
    @Test void deliveriesRequireConfirmationPermissionAndSelectedParent() throws Exception {
        var f=fixture(); assertThatThrownBy(()->projects.upload(f.owner,f.project,image("edited.jpg"),"edited",MediaKind.DELIVERY,f.media)).isInstanceOf(ApiProblem.class);
        projects.saveSelection(f.share,f.grant,version(f),List.of(new ProjectService.Choice(f.media,true,"")));
        String revision=Db.str(projects.submit(f.share,f.grant,version(f)),"revisionId"); projects.confirm(f.owner,f.project,revision);
        var delivery=projects.upload(f.owner,f.project,image("edited.jpg"),"edited",MediaKind.DELIVERY,f.media); String deliveryId=Db.str(delivery,"id");
        fail(()->projects.publicFile(f.share,f.grant,deliveryId,true),403);
        var share=db.one("SELECT id FROM shares WHERE project_id=?",f.project); projects.updateShare(f.owner,f.project,Db.str(share,"id"),null,true,false);
        assertThat(projects.publicFile(f.share,f.grant,deliveryId,true).get("id")).isEqualTo(deliveryId);
        projects.reopen(f.owner,f.project); fail(()->projects.publicFile(f.share,f.grant,deliveryId,true),403);
    }
    @Test void quotaFailureDoesNotPublishMediaOrConsumeSpace() throws Exception {
        String owner=account(),project=Db.str(projects.create(owner,"额度测试"),"id"); db.update("UPDATE accounts SET quota_bytes=1 WHERE id=?",owner);
        assertThatThrownBy(()->projects.upload(owner,project,image("large.png"),"source",MediaKind.PREVIEW,null)).isInstanceOf(ApiProblem.class);
        assertThat(Db.num(auth.me(owner),"usedBytes")).isZero(); assertThat(db.rows("SELECT id FROM media WHERE project_id=?",project)).isEmpty();
    }
    @Test void sourceIdentityMakesRetriesIdempotentAndNamesDoNotCollide() throws Exception {
        var f=fixture(); long used=Db.num(auth.me(f.owner),"usedBytes");
        var repeated=projects.upload(f.owner,f.project,image("portrait.png"),"camera:card:1",MediaKind.PREVIEW,null);
        assertThat(repeated.get("id")).isEqualTo(f.media); assertThat(Db.num(auth.me(f.owner),"usedBytes")).isEqualTo(used);
        var next=projects.upload(f.owner,f.project,image("portrait.png"),"camera:card:2",MediaKind.PREVIEW,null); assertThat(next.get("id")).isNotEqualTo(f.media);
    }
    @Test void concurrentSavesAcceptExactlyOneVersion() throws Exception {
        var f=fixture(); int current=version(f); var pool=Executors.newFixedThreadPool(2);
        try { var jobs=pool.invokeAll(List.of(()->{try{projects.saveSelection(f.share,f.grant,current,List.of(new ProjectService.Choice(f.media,true,"A")));return true;}catch(ApiProblem p){return false;}},()->{try{projects.saveSelection(f.share,f.grant,current,List.of(new ProjectService.Choice(f.media,true,"B")));return true;}catch(ApiProblem p){return false;}})); int accepted=0; for(var job:jobs) { if(Boolean.TRUE.equals(job.get())) { accepted++; } } assertThat(accepted).isEqualTo(1); assertThat(version(f)).isEqualTo(current+1); } finally { pool.shutdownNow(); }
    }
}
