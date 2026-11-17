import os, time, tempfile, requests
from fastapi import FastAPI, UploadFile, File, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv
from openai import OpenAI

load_dotenv()
ASSEMBLYAI_API_KEY=os.getenv("ASSEMBLYAI_API_KEY","")
OPENAI_API_KEY=os.getenv("OPENAI_API_KEY","")\nAPP_ACCESS_TOKEN=os.getenv("APP_ACCESS_TOKEN","")
app=FastAPI(title="Truth Analyzer API")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["POST"], allow_headers=["*"])

def require_keys():
    if not ASSEMBLYAI_API_KEY or not OPENAI_API_KEY:
        raise HTTPException(503,"يجب إعداد مفاتيح خدمات التفريغ والتحليل على الخادم.")

def transcribe_with_speakers(path):
    headers={"authorization":ASSEMBLYAI_API_KEY}
    with open(path,"rb") as f:
        up=requests.post("https://api.assemblyai.com/v2/upload",headers=headers,data=f,timeout=300)
    up.raise_for_status()
    audio_url=up.json()["upload_url"]
    job=requests.post("https://api.assemblyai.com/v2/transcript",
        headers={**headers,"content-type":"application/json"},
        json={"audio_url":audio_url,"language_code":"ar","speaker_labels":True,
              "punctuate":True,"format_text":True},timeout=60)
    job.raise_for_status()
    tid=job.json()["id"]
    for _ in range(180):
        r=requests.get(f"https://api.assemblyai.com/v2/transcript/{tid}",headers=headers,timeout=30)
        r.raise_for_status(); data=r.json()
        if data["status"]=="completed": return data
        if data["status"]=="error": raise HTTPException(502,"فشل التفريغ: "+str(data.get("error","unknown")))
        time.sleep(2)
    raise HTTPException(504,"انتهت مهلة انتظار التفريغ الصوتي.")

def stamp(ms):
    sec=ms//1000
    return f"{sec//60:02d}:{sec%60:02d}"

@app.post("/analyze")
async def analyze(audio: UploadFile=File(...), language: str="ar"):
    require_keys()
    suffix=".m4a" if (audio.filename or "").lower().endswith((".m4a",".mp4")) else ".audio"
    tmp=None
    try:
        with tempfile.NamedTemporaryFile(delete=False,suffix=suffix) as f:
            tmp=f.name
            while chunk:=await audio.read(1024*1024): f.write(chunk)
        data=transcribe_with_speakers(tmp)
        utterances=data.get("utterances") or []
        lines=[]
        for u in utterances:
            speaker=u.get("speaker") or "غير محدد"
            lines.append(f"[{stamp(u['start'])}–{stamp(u['end'])}] المتحدث {speaker}: {u['text']}")
        transcript="\n".join(lines) if lines else data.get("text","")
        prompt=f"""حلل هذا الحوار العربي بحياد. لا تحدد من الكاذب ولا تستنتج الكذب من الصوت أو التردد.
استخرج: ملخصاً محايداً، الادعاءات المتفق عليها، الادعاءات المتعارضة، كل تعارض مع توقيت الطرفين واقتباس قصير، أسئلة توضيحية، وأدلة مستقلة لازمة للتحقق.
فرّق بين التناقض الحقيقي واختلاف الذاكرة أو غموض التعبير. إذا لم توجد أدلة كافية فقل ذلك صراحة.
أعد التقرير بالعربية الواضحة وبعناوين مرتبة. التوقيتات مأخوذة من التفريغ وقد تحتاج مراجعة.
نص الحوار:
{transcript}"""
        client=OpenAI(api_key=OPENAI_API_KEY)
        response=client.chat.completions.create(
            model=os.getenv("OPENAI_MODEL","gpt-4o-mini"),
            messages=[{"role":"system","content":"أنت محلل محايد للأقوال. لا تصدر أحكاماً على صدق الأشخاص."},
                      {"role":"user","content":prompt}],
            temperature=0.2)
        report=response.choices[0].message.content or "تعذر إنشاء التقرير."
        return {"transcript":transcript,"report":report,"speakers":sorted(set(u.get("speaker","?") for u in utterances))}
    except HTTPException: raise
    except Exception as e: raise HTTPException(502,"خطأ أثناء التحليل: "+str(e))
    finally:
        if tmp and os.path.exists(tmp): os.remove(tmp)
