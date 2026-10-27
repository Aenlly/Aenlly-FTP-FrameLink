package app.framelink.cloud;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PreviewImageTest {
    @Test void portraitExifIsAppliedBeforeMetadataRemoval() throws Exception {
        var jpeg=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(120,80,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg);
        byte[] exif={69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        var source=new ByteArrayOutputStream(); source.write(jpeg.toByteArray(),0,2); source.write(255); source.write(225); source.write(0); source.write(exif.length+2); source.write(exif); source.write(jpeg.toByteArray(),2,jpeg.size()-2);
        int orientation=PreviewImage.orientation(new ByteArrayInputStream(source.toByteArray())); assertThat(orientation).isEqualTo(6);
        byte[] preview=PreviewImage.create(new ByteArrayInputStream(source.toByteArray()),orientation);
        var result=ImageIO.read(new ByteArrayInputStream(preview)); assertThat(result.getWidth()).isEqualTo(80); assertThat(result.getHeight()).isEqualTo(120);
        assertThat(PreviewImage.orientation(new ByteArrayInputStream(preview))).isEqualTo(1);
    }
    @Test void invalidFileDoesNotBecomeAnImage() { assertThatThrownBy(()->PreviewImage.create(new ByteArrayInputStream("not an image".getBytes()))).isInstanceOf(ApiProblem.class); }
}
