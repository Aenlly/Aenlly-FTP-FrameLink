package app.framelink.cloud;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** 按需采样并重新编码预览，移除GPS和其他原片元数据 */
public final class PreviewImage {
    private PreviewImage() { }
    public static byte[] create(InputStream input) throws IOException {
        return create(input,1);
    }
    public static int orientation(InputStream input) {
        try(input) { var directory=ImageMetadataReader.readMetadata(input).getFirstDirectoryOfType(ExifIFD0Directory.class); return directory==null?1:directory.getInt(ExifIFD0Directory.TAG_ORIENTATION); }
        catch(Exception ignored) { return 1; }
    }
    public static byte[] create(InputStream input,int orientation) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream); ApiProblem.require(readers.hasNext(), 400, "云端预览请使用JPEG或PNG，RAW和HEIF请先在App生成JPEG预览");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream); int width = reader.getWidth(0), height = reader.getHeight(0);
                ApiProblem.require(width > 0 && height > 0 && (long)width*height <= ProductPolicy.MAX_IMAGE_PIXELS, 400, "图片尺寸超出预览处理范围");
                var params = reader.getDefaultReadParam(); int sample = Math.max(1, Math.max(width, height)/ProductPolicy.PREVIEW_EDGE); params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, params); double scale = Math.min(1d, (double)ProductPolicy.PREVIEW_EDGE/Math.max(image.getWidth(), image.getHeight()));
                BufferedImage preview = new BufferedImage(Math.max(1,(int)(image.getWidth()*scale)), Math.max(1,(int)(image.getHeight()*scale)), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = preview.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0,0,preview.getWidth(),preview.getHeight()); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); graphics.drawImage(image,0,0,preview.getWidth(),preview.getHeight(),null); graphics.dispose();
                BufferedImage normalized=preview;
                if(orientation>=2 && orientation<=8) {
                    int w=preview.getWidth(),h=preview.getHeight(); boolean swap=orientation>=5;
                    normalized=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_RGB);
                    AffineTransform transform=new AffineTransform();
                    switch(orientation) {
                        case 2 -> { transform.translate(w,0); transform.scale(-1,1); }
                        case 3 -> { transform.translate(w,h); transform.rotate(Math.PI); }
                        case 4 -> { transform.translate(0,h); transform.scale(1,-1); }
                        case 5 -> { transform.rotate(Math.PI/2); transform.scale(1,-1); }
                        case 6 -> { transform.translate(h,0); transform.rotate(Math.PI/2); }
                        case 7 -> { transform.translate(h,w); transform.rotate(Math.PI/2); transform.scale(-1,1); }
                        case 8 -> { transform.translate(0,w); transform.rotate(-Math.PI/2); }
                        default -> { }
                    }
                    Graphics2D oriented=normalized.createGraphics(); oriented.drawImage(preview,transform,null); oriented.dispose();
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(normalized,"jpeg",output); image.flush(); preview.flush(); if(normalized!=preview)normalized.flush(); return output.toByteArray();
            } finally { reader.dispose(); }
        }
    }
}
