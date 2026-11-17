package com.example.truthanalyzer;

import android.Manifest;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private MediaRecorder recorder;
    private File audioFile;
    private TextView status, result;
    private EditText endpoint, accessToken, p1, p2;
    private Button recordBtn, stopBtn, analyzeBtn, pdfBtn;
    private String lastReport = "";
    private String transcript = "";
    private final String boundary = "----TruthAnalyzerBoundary7MA4YWxk";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status=findViewById(R.id.status); result=findViewById(R.id.result);
        p1=findViewById(R.id.person1); p2=findViewById(R.id.person2);
        endpoint=findViewById(R.id.endpoint); accessToken=findViewById(R.id.accessToken);
        recordBtn=findViewById(R.id.recordBtn); stopBtn=findViewById(R.id.stopBtn);
        analyzeBtn=findViewById(R.id.analyzeBtn); pdfBtn=findViewById(R.id.pdfBtn);
        recordBtn.setOnClickListener(v -> startRecording());
        stopBtn.setOnClickListener(v -> stopRecording());
        analyzeBtn.setOnClickListener(v -> analyzeOnline());
        pdfBtn.setOnClickListener(v -> exportPdf());
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 77); return;
        }
        try {
            File dir=new File(getExternalFilesDir(null),"recordings"); if(!dir.exists()) dir.mkdirs();
            audioFile=new File(dir,"dialogue_"+System.currentTimeMillis()+".m4a");
            recorder=new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare(); recorder.start();
            status.setText("جارٍ التسجيل… احصل على موافقة جميع المشاركين.");
            recordBtn.setEnabled(false); stopBtn.setEnabled(true);
            pdfBtn.setEnabled(false);
        } catch(Exception e) { status.setText("تعذر بدء التسجيل: "+e.getMessage()); releaseRecorder(); }
    }

    private void stopRecording() {
        try {
            if(recorder!=null) { recorder.stop(); status.setText("تم حفظ التسجيل محلياً: "+audioFile.getName()); }
        } catch(Exception e) { status.setText("التسجيل قصير جداً أو لم يكتمل."); audioFile=null; }
        finally { releaseRecorder(); recordBtn.setEnabled(true); stopBtn.setEnabled(false); }
    }
    private void releaseRecorder() { if(recorder!=null){ recorder.release(); recorder=null; } }

    private void analyzeOnline() {
        if(audioFile==null || !audioFile.exists()) {
            status.setText("سجّل الحوار أولاً ثم أوقف التسجيل قبل التحليل."); return;
        }
        String base=endpoint.getText().toString().trim();
        if(base.isEmpty() || !base.startsWith("https://")) {
            status.setText("أدخل رابط خادم التحليل الآمن HTTPS أولاً."); return;
        }
        analyzeBtn.setEnabled(false); status.setText("جارٍ رفع التسجيل وتفريغه وتمييز المتحدثين…");
        new Thread(() -> {
            HttpURLConnection c=null;
            try {
                URL url=new URL(base.replaceAll("/+$","")+"/analyze");
                c=(HttpURLConnection)url.openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(30000); c.setReadTimeout(180000);
                c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);\n                c.setRequestProperty("X-App-Token",accessToken.getText().toString().trim());
                try(DataOutputStream out=new DataOutputStream(c.getOutputStream())) {
                    writeField(out,"language","ar");
                    out.writeBytes("--"+boundary+"\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"dialogue.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n");
                    try(FileInputStream in=new FileInputStream(audioFile)){ byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n); }
                    out.writeBytes("\r\n--"+boundary+"--\r\n"); out.flush();
                }
                int code=c.getResponseCode();
                InputStream stream=(code>=200&&code<300)?c.getInputStream():c.getErrorStream();
                String response=readAll(stream);
                if(code<200||code>=300) throw new Exception("الخادم أعاد "+code+": "+response);
                org.json.JSONObject json=new org.json.JSONObject(response);
                transcript=json.optString("transcript","");
                String report=json.optString("report",transcript);
                StringBuilder formatted=new StringBuilder();
                formatted.append("تفريغ الحوار وتمييز المتحدثين\n\n").append(transcript);
                formatted.append("\n\nتحليل الاتساق والتناقضات\n\n").append(report);
                lastReport=formatted.toString();
                runOnUiThread(() -> { result.setText(lastReport); status.setText("اكتمل التحليل. راجع التفريغ وهوية المتحدثين قبل اعتماد التقرير."); pdfBtn.setEnabled(true); analyzeBtn.setEnabled(true); });
            } catch(Exception e) {
                runOnUiThread(() -> { status.setText("فشل التحليل: "+e.getMessage()); analyzeBtn.setEnabled(true); });
            } finally { if(c!=null)c.disconnect(); }
        }).start();
    }
    private void writeField(DataOutputStream out,String name,String value)throws IOException{
        out.writeBytes("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n");
    }
    private String readAll(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n;
        while((n=in.read(buf))!=-1)b.write(buf,0,n);
        return new String(b.toByteArray(),StandardCharsets.UTF_8);
    }

    private void exportPdf() {
        if(lastReport.isEmpty()){ status.setText("لا يوجد تقرير لتصديره."); return; }
        try {
            PdfDocument pdf=new PdfDocument();
            Paint paint=new Paint(); paint.setTextSize(12); paint.setColor(0xff163746); paint.setTextAlign(Paint.Align.RIGHT);
            String[] lines=lastReport.split("\\n");
            int pageNo=1, y=50; PdfDocument.Page page=null; Canvas canvas=null;
            for(String line:lines){
                if(page==null || y>780){
                    if(page!=null) pdf.finishPage(page);
                    PdfDocument.PageInfo info=new PdfDocument.PageInfo.Builder(595,842,pageNo++).create();
                    page=pdf.startPage(info); canvas=page.getCanvas(); y=48;
                    paint.setTextSize(17); paint.setFakeBoldText(true); canvas.drawText("Truth Analyzer - تقرير تحليل الحوار",560,y,paint); y+=32;
                    paint.setTextSize(12); paint.setFakeBoldText(false);
                }
                String s=line;
                // Basic Latin-safe PDF fallback. Arabic shaping depends on the device font renderer.
                canvas.drawText(s,560,y,paint); y+=20;
            }
            if(page!=null)pdf.finishPage(page);
            File dir=new File(getExternalFilesDir(null),"reports"); if(!dir.exists())dir.mkdirs();
            File file=new File(dir,"TruthAnalyzer_"+System.currentTimeMillis()+".pdf");
            try(FileOutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}
            pdf.close(); status.setText("تم حفظ PDF داخل مجلد التطبيق: "+file.getAbsolutePath());
        } catch(Exception e){ status.setText("تعذر إنشاء PDF: "+e.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] grants) {
        super.onRequestPermissionsResult(req,perms,grants);
        if(req==77 && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) startRecording();
        else status.setText("يجب السماح بإذن الميكروفون للتسجيل.");
    }
    @Override protected void onDestroy(){ if(recorder!=null) releaseRecorder(); super.onDestroy(); }
}
