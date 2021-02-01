package com.honvay.hdms.dms.web;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.honvay.hdms.auth.core.AuthenticatedUser;
import com.honvay.hdms.config.properties.StorageConfig;
import com.honvay.hdms.dms.authorize.authentication.annotation.Authentication;
import com.honvay.hdms.dms.document.entity.Document;
import com.honvay.hdms.dms.document.service.DocumentReadService;
import com.honvay.hdms.dms.encryptor.Encryptors;
import com.honvay.hdms.dms.event.DownloadEvent;
import com.honvay.hdms.dms.permission.enums.PermissionType;
import com.honvay.hdms.dms.storage.Storage;
import com.honvay.hdms.dms.storage.StorageDirectory;
import com.honvay.hdms.dms.token.AccessTokenStore;
import com.honvay.hdms.framework.utils.ServletUtils;
import com.honvay.hdms.framework.utils.Xssf2Hssf;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.converter.ExcelToHtmlConverter;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.PicturesManager;
import org.apache.poi.hwpf.converter.WordToHtmlConverter;
import org.apache.poi.hwpf.converter.WordToHtmlUtils;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.PictureType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.converter.core.BasicURIResolver;
import org.apache.poi.xwpf.converter.core.FileImageExtractor;
import org.apache.poi.xwpf.converter.core.FileURIResolver;
import org.apache.poi.xwpf.converter.xhtml.XHTMLConverter;
import org.apache.poi.xwpf.converter.xhtml.XHTMLOptions;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author LIQIU
 */
@Controller
@RequestMapping("/fs")
@Slf4j
public class AccessController {

	private static final String XLS = "xls";
	private static final String XLSX = "xlsx";

	@Autowired
	private DocumentReadService documentReadService;

	@Autowired
	private Encryptors encryptors;

	@Autowired
	private Storage storage;

	@Autowired
	private StorageConfig storageConfig;

	@Autowired
	private ApplicationEventPublisher applicationEventPublisher;

	@Autowired
	private AccessTokenStore accessTokenStore;

	private Cache<String, Document> cache = CacheBuilder.newBuilder()
			.maximumSize(100)
			.expireAfterWrite(1, TimeUnit.MINUTES)
			.concurrencyLevel(10)
			.build();

	@RequestMapping("/stream")
	public void stream(String token, Integer id,HttpServletResponse response) throws IOException {
		Document document = documentReadService.get(id);
		if (document != null) {
			ServletUtils.setFileDownloadHeader(response, document.getName());
			response.setContentType(document.getContentType());
			response.setContentLength(document.getSize().intValue());
			IOUtils.copy(storage.getInputStream(StorageDirectory.FILE, document.getCode()), response.getOutputStream());
		}
	}

	@RequestMapping("/office")
	public void preview(@RequestParam Integer id, HttpServletRequest request,HttpServletResponse response) {
		String token = UUID.randomUUID().toString();
		Document doc = documentReadService.get(id);
		String code = doc.getCode();
		// this.cache.put(token, documentReadService.get(id));
		// String officePreviewServer = "https://view.officeapps.live.com/op/view.aspx?src=";
		// String officePreviewServer = "http://view.officeapps.live.com/op/view.aspx?src=";

		// String officePreviewServer = "http://127.0.0.1:8012/onlinePreview?url=";
        // String basePath = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() +
		// request.getContextPath() + "/fs/stream?token=";
//		String url =
//				request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath() + "/file/"+code;
		//String url = EncodeUtils.urlEncode(path);
		//log.debug("url is : " + url);

		try {
			String location = storageConfig.getLocation();
			String path = location+File.separator+StorageDirectory.FILE +File.separator+ code;
			log.debug("path is 》》》》。 : " + path);
			String htmlStr = null;
			String contentType = doc.getContentType();
			log.debug("contentType is >>>> " + contentType);
			if("application/msword".equals(contentType)||("application/vnd.openxmlformats-officedocument" +
					".wordprocessingml.document").equals(contentType)){
				htmlStr = convertWordToHtml(path,location);
			}else {
				htmlStr = convertExceltoHtml(path);
			}


			htmlStr = htmlStr.replace("<h2>Sheet1</h2>", "").replace("<h2>Sheet2</h2>", "")
					.replace("<h2>Sheet3</h2>", "").replace("<h2>Sheet4</h2>", "").replace("<h2>Sheet5</h2>", "");
			response.setContentType("text/html;charset=utf-8");
			PrintWriter pw = response.getWriter();
			pw.print(htmlStr);
			pw.flush();
			pw.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		//return "redirect:" + (url);
	}

	/**
	 *  excel 转 html
	 * @param path
	 * @return
	 * @throws Exception
	 */
	public static String convertExceltoHtml(String path) throws Exception {
		HSSFWorkbook workBook = null;
		String content = null;
		StringWriter writer = null;
		File excelFile = new File(path);
		InputStream is = new FileInputStream(excelFile);
		String suffix = path.substring(path.lastIndexOf("."));
		if(suffix.equals("."+XLSX)){
			//将07版转化为03版
			Xssf2Hssf xlsx2xls = new Xssf2Hssf();
			XSSFWorkbook xSSFWorkbook = new XSSFWorkbook(is);
			workBook = new HSSFWorkbook();
			xlsx2xls.transformXSSF(xSSFWorkbook, workBook);
		}else{
			workBook = new HSSFWorkbook(is);
		}
		try {
			ExcelToHtmlConverter converter = new ExcelToHtmlConverter(DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument());
			converter.setOutputColumnHeaders(false);// 不显示列的表头
			converter.setOutputRowNumbers(false);// 不显示行的表头
			converter.processWorkbook(workBook);

			writer = new StringWriter();
			Transformer serializer = TransformerFactory.newInstance().newTransformer();
			serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			serializer.setOutputProperty(OutputKeys.INDENT, "yes");
			serializer.setOutputProperty(OutputKeys.METHOD, "html");
			serializer.transform(new DOMSource(converter.getDocument()),
					new StreamResult(writer));
			content = writer.toString();
			writer.close();
		} finally {
			try {
				if (is != null) {
					is.close();
				}
				if (writer != null) {
					writer.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return content;
	}

	/**
	 * word to html
	 * @param path
	 * @param location
	 * @return
	 * @throws Exception
	 */
	public static String convertWordToHtml(String path,String location) throws Exception{
		FileInputStream fis = new FileInputStream(path);
		// 需要判断后缀是doc还是docx
		String suffix = path.substring(path.lastIndexOf(".")+1,path.length());
		log.debug("suffix >>>>>>>>>> " + suffix);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if(suffix.toLowerCase().equals("doc")){
			HWPFDocument wordDocument = new HWPFDocument(fis);
			WordToHtmlConverter wordToHtmlConverter =
					new WordToHtmlConverter(DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument());
			wordToHtmlConverter.setPicturesManager(new PicturesManager() {
				@Override
				public String savePicture(byte[] content, PictureType pictureType, String suggestedName, float widthInches, float heightInches) {
					return "test/"+suggestedName;
				}
			});

			wordToHtmlConverter.processDocument(wordDocument);
			// save Pictures
			List pics = wordDocument.getPicturesTable().getAllPictures();
			if(pics!=null){
				for (int i = 0; i <pics.size() ; i++) {
					Picture picture = (Picture) pics.get(i);
					picture.writeImageContent(new FileOutputStream(location+File.separator+picture.suggestFullFileName()));
				}
			}
			org.w3c.dom.Document document = wordToHtmlConverter.getDocument();
			DOMSource domSource = new DOMSource(document);
			StreamResult streamResult = new StreamResult(out);

			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer serializer = tf.newTransformer();
			serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			serializer.setOutputProperty(OutputKeys.INDENT, "yes");
			serializer.setOutputProperty(OutputKeys.METHOD, "html");
			serializer.transform(domSource,streamResult);

		}else {
			// docx
			XWPFDocument document = new XWPFDocument(new FileInputStream(path));
			XHTMLOptions options = XHTMLOptions.create();
			String property = "java.io.tmpdir";
			String tempDir = System.getProperty(property);
			log.debug("tempDir >>>>>>>>>>>>>>> " + tempDir);
			// 导出图片
			File imageFolder = new File(tempDir);
			options.setExtractor(new FileImageExtractor(imageFolder));
			options.URIResolver(new BasicURIResolver("image"));
			// URI resolver
			//options.URIResolver(new FileURIResolver(imageFolder));
			//File outFile = new File(fileOutName);
			//outFile.getParentFile().mkdirs();
			XHTMLConverter.getInstance().convert(document, out, options);
		}

		out.close();

		return new String(out.toByteArray());
		//wirteFile(new String(out.toByteArray()),path);
	}

	/*public static void wirteFile(String content,String path) {
		FileOutputStream fos = null;
		BufferedWriter bw = null;
		org.jsoup.nodes.Document doc = Jsoup.parse(content);
		content = doc.html();
		try {
			File file = new File(path);
			fos = new FileOutputStream(file);
			bw = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
			bw.write(content);
		}catch (Exception ioe){
			log.error("{}",ioe.getMessage());
		}finally {
			try {
				if(bw!=null){
					bw.close();
				}
				if(fos!=null){
					fos.close();
				}
			}catch (Exception e){}
		}

	}*/

	/**
	 * 根据文件后缀名类型获取对应的工作簿对象
	 *
	 * @param excelFile 读取文件
	 * @return 包含文件数据的工作簿对象
	 * @throws IOException
	 */
	public static Workbook getWorkbook(File excelFile) throws IOException {
		// 获取Excel后缀名
		String fileName = excelFile.getAbsolutePath();
		String fileType = fileName.substring(fileName.lastIndexOf(".") + 1, fileName.length());
		// 获取Excel工作簿
		FileInputStream inputStream = new FileInputStream(excelFile);
		Workbook workbook = null;
		if (fileType.equalsIgnoreCase(XLS)) {
			workbook = new HSSFWorkbook(inputStream);
		} else if (fileType.equalsIgnoreCase(XLSX)) {
			workbook = new XSSFWorkbook(inputStream);
		}
		return workbook;
	}

	@RequestMapping("/raw")
	public void raw(String token, HttpServletResponse response) throws Exception {
		String code = accessTokenStore.get(token);
		if (code != null) {
			IOUtils.copy(storage.getInputStream(StorageDirectory.FILE, code), response.getOutputStream());
		}
	}

	@RequestMapping("/download")
	@Authentication(value = PermissionType.DOWNLOAD, multiple = true)
	public void download(@RequestParam Integer id, HttpServletResponse response,
						 @AuthenticationPrincipal AuthenticatedUser authenticatedUser) throws Exception {
		Document document = documentReadService.get(id);
		DownloadEvent downloadEvent = new DownloadEvent(document, authenticatedUser.getId());
		applicationEventPublisher.publishEvent(downloadEvent);
		ServletUtils.setFileDownloadHeader(response, document.getName());
		encryptors.decrypt(storage.getInputStream(StorageDirectory.FILE, document.getCode()), response.getOutputStream());
	}
}
