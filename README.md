# Site Scanner 3D (สแกนหน้างาน 3D)

แอป Android สำหรับ**สแกนพื้นที่หน้างานเป็น 3 มิติ** แล้วนำขนาดไปทำ **Shop Drawing**
ใช้ ARCore (Depth API) ของ Google สแกนห้อง/พื้นที่จริงเป็น point cloud, วัดระยะด้วย AR,
สร้างแปลนพื้น (floor plan) พร้อมเส้นบอกขนาดอัตโนมัติ และส่งออกไฟล์ไปเปิดใน AutoCAD / Revit / SketchUp

> สถานะ: **v0.1 (โครงเริ่มต้น)** ใช้งานได้ครบวงจร สแกน → ดู 3D → แปลน → ส่งออก
> แต่ยังต้องทดสอบภาคสนามบนมือถือจริง ดู [แผนพัฒนาต่อ](#แผนพัฒนาต่อ-roadmap)

## ความสามารถ

| ส่วน | รายละเอียด |
|---|---|
| **โปรเจกต์** | แยกตามหน้างาน (ชื่อ, สถานที่, หมายเหตุ) แต่ละโปรเจกต์มีได้หลายสแกน |
| **สแกน 3D** | รวมภาพ depth จาก ARCore Raw Depth เป็น point cloud สี (voxel 1 ซม., ถ่วงน้ำหนักตามความมั่นใจของ depth) — มือถือที่ไม่มี Depth API จะใช้ feature points แทน (ห่างกว่า) |
| **วัดระยะ AR** | เล็งเป้ากลางจอ กด *วัดระยะ* 2 ครั้ง ได้ระยะจุดต่อจุดเป็น มม. (ยึดด้วย ARCore Anchor) ตั้งชื่อระยะได้ เช่น "ความกว้างประตู" |
| **ดู 3D** | หมุน/เลื่อน/ซูม point cloud, สลับสีจริง ↔ สีตามความสูง, แสดงเส้นระยะที่วัด |
| **แปลนพื้นอัตโนมัติ** | หาระดับพื้น-ฝ้า, ตัดระนาบแนวนอนผ่านผนัง, หาแนวผนังหลักแล้วหมุนให้ขนานแกน, สกัดผนัง (รวมผนังเฉียง), ต่อมุมให้ปิด, แสดงความยาวผนังเป็น มม. และความสูงพื้นถึงฝ้า |
| **ส่งออก** | DXF แปลน (AutoCAD), PLY / PTS point cloud (CloudCompare, ReCap → Revit/AutoCAD), OBJ ผนัง 3D (SketchUp, Blender), CSV ตารางระยะ (Excel) — แชร์ผ่าน LINE, Gmail, Drive ได้ทันที |
| **ภาษา** | ไทย / อังกฤษ ตามภาษาของเครื่อง |

### ไฟล์ส่งออกใช้ทำอะไร

| ไฟล์ | เปิดด้วย | ใช้ทำ |
|---|---|---|
| `*_plan.dxf` | AutoCAD, BricsCAD, ZWCAD, LibreCAD | แปลนผนัง + เส้นบอกขนาด (layer `A-WALL`, `A-DIMS`), ระยะที่วัด (`A-MEAS`), จุดสแกนสำหรับลากเส้นทับ (`A-SCAN-SLICE`) หน่วย **มิลลิเมตร** |
| `*_points.pts` | Autodesk ReCap → Revit / AutoCAD | point cloud เต็ม ใช้ลากทับทำ shop drawing / ตรวจระยะ |
| `*_points.ply` | CloudCompare, MeshLab, Blender | point cloud สี |
| `*_walls.obj` | SketchUp, Blender, Revit | ผนังเป็นแผ่น 3D สูงพื้นถึงฝ้า |
| `*_measurements.csv` | Excel, Google Sheets | ตารางระยะที่วัด (มม.) พร้อมพิกัดและ dx/dy/dz |

**ทุกไฟล์ของสแกนเดียวกันใช้ระบบพิกัดเดียวกัน** (Z ชี้ขึ้น, พื้น = 0, ผนังหลักขนานแกน X/Y)
จึงซ้อนกันใน CAD ได้พอดี

## อุปกรณ์ที่รองรับ

- Android 7.0 (API 24) ขึ้นไป ที่ [รองรับ ARCore](https://developers.google.com/ar/devices)
- แนะนำรุ่นที่รองรับ **Depth API** (ในรายการเดียวกันระบุ "Supports Depth API") เพื่อได้ point cloud หนาแน่น
- ต้องมี *Google Play Services for AR* (แอปจะพาไปติดตั้งให้อัตโนมัติ)
- มือถือที่ไม่รองรับ ARCore ยังเปิดดู/ส่งออกสแกนเดิมได้ แต่สแกนใหม่ไม่ได้

## ติดตั้ง APK

1. เปิดแท็บ **Actions** ของ repo นี้ → เลือก run ล่าสุดของ *Android CI* ที่ผ่าน (✅)
2. ดาวน์โหลด artifact **site-scanner-debug-apk** (ไฟล์ zip) แล้วแตกไฟล์ได้ `app-debug.apk`
3. ส่งไฟล์เข้ามือถือ → เปิดติดตั้ง (อนุญาต *ติดตั้งแอปจากแหล่งที่ไม่รู้จัก* ครั้งแรก)

## วิธีสแกนให้แม่น

1. สร้างโปรเจกต์ → กด **สแกน** → ขยับมือถือช้า ๆ จนสถานะขึ้น "พร้อม"
2. กด **บันทึก** (ปุ่มกลม) แล้วเดินช้า ๆ กวาดกล้องไปตามผนัง **ห่างพื้นผิว 1–3 เมตร**
   (depth แม่นที่สุดในช่วงนี้; ไกลเกิน 4 ม. จะไม่ถูกเก็บ)
3. ให้กล้องเห็น **ผนังช่วงระดับเข่าถึงศีรษะ** ครบทุกด้าน, พื้น และฝ้า — แปลนคำนวณจากช่วงนี้
4. แสงต้องพอ หลีกเลี่ยงกระจก/ผิวมันวาว/ผนังขาวเรียบไม่มีลาย (ติดเทปหรือกระดาษช่วยได้)
5. ระยะสำคัญ (ช่องเปิด, ระยะติดตั้ง) ให้ **วัดด้วยปุ่มวัดระยะ** เพิ่ม แล้วตั้งชื่อไว้
6. กด ✓ บันทึก — ห้องใหญ่/หลายห้องให้แบ่งเป็นหลายสแกน

> ⚠️ **ความแม่นยำ:** ARCore depth บนมือถือทั่วไปคลาดเคลื่อนประมาณ 1–3% ของระยะ
> และสะสมเมื่อเดินไกล เหมาะกับการเก็บข้อมูลหน้างานเบื้องต้นและทำแบบร่าง
> **ระยะวิกฤตต้องตรวจสอบด้วยตลับเมตร/เลเซอร์ก่อนสั่งผลิตเสมอ**

## โครงสร้างโปรเจกต์

```
core/   Kotlin/JVM ล้วน (ไม่พึ่ง Android) — ทดสอบด้วย unit test
  geometry/    Vec2/Vec3, Mat4 (column-major แบบ OpenGL/ARCore)
  pointcloud/  DepthUnprojector (depth → จุด 3D), VoxelPointCloud (fusion),
               ScanIntegrator (thread-safe), KeyframeSelector, YuvFrame (สีจากกล้อง)
  floorplan/   LevelEstimator (พื้น/ฝ้า), FloorPlanExtractor (ผนัง), SiteAlignment (ระบบพิกัดงาน)
  export/      Ply, Pts, DxfDocument + FloorPlanDxf, WallsObj, MeasurementCsv
  project/     Project/ScanInfo/Measurement + ProjectRepository (ไฟล์ JSON + PLY)
  viewer/      OrbitCamera
app/    Android (Jetpack Compose + ARCore + OpenGL ES 2)
  scan/        ScanActivity (ARCore session), ScanRenderer (GL thread: กล้อง, จุด, วัดระยะ),
               FrameCapture (คัดลอก depth/สี), ScanController (สถานะ + fusion worker), ScanScreen (UI)
  gl/          BackgroundRenderer, PointRenderer, LineRenderer
  ui/          projects, project, viewer, floorplan, theme
  data/        ScanAnalysis (โหลด/แคช cloud + แปลน), ExportManager (เขียนไฟล์ + แชร์)
```

ข้อมูลเก็บในเครื่องที่ `files/projects/<projectId>/project.json` และ `scan_<id>.ply`
(point cloud เก็บในพิกัด ARCore ของ session นั้น; แปลงเป็นพิกัดงานตอนส่งออก)

### หลักการทำงานโดยย่อ

1. **Fusion:** ทุก keyframe (ขยับ ≥3 ซม. หรือหมุน ≥3°) ดึง raw depth + confidence + ภาพสี
   → back-project แต่ละ pixel เป็นจุดในโลก → รวมลง voxel grid 1 ซม. แบบถ่วงน้ำหนัก confidence
   (ลด noise; voxel ที่เห็นครั้งเดียวแบบไม่มั่นใจจะถูกตัดตอนบันทึก)
2. **แปลน:** histogram ความสูง → พื้น/ฝ้า; ตัดช่วง 0.3–1.8 ม. เหนือพื้นเป็น occupancy grid 2.5 ซม.;
   หามุมผนังหลักที่ทำให้ projection "คม" ที่สุด; ดึงแนวผนังขนานแกน + RANSAC สำหรับผนังเฉียง;
   ตัดช่วงว่าง >30 ซม. (ประตู); ต่อปลายผนังเข้ามุม

## Build จาก source

ต้องมี JDK 17+ และ Android SDK (API 36)

```bash
./gradlew :core:test          # unit test ของ core (ไม่ต้องมี Android SDK)
./gradlew :app:assembleDebug  # ได้ app/build/outputs/apk/debug/app-debug.apk
```

หรือเปิดโฟลเดอร์นี้ด้วย Android Studio แล้วกด Run
GitHub Actions (`.github/workflows/android.yml`) รัน test และ build APK ทุกครั้งที่ push

## แผนพัฒนาต่อ (Roadmap)

- [ ] ทดสอบภาคสนามบนมือถือจริง, ปรับค่า filter/threshold ตามผล
- [ ] ตรวจจับช่องเปิด (ประตู/หน้าต่าง) พร้อมขนาดและระดับขอบล่าง/บน
- [ ] รูปด้าน (elevation) ของผนังแต่ละด้าน สำหรับ shop drawing งานติดตั้งบนผนัง
- [ ] รวมหลายสแกนเข้าด้วยกัน (registration) / สแกนต่อจากจุดเดิม
- [ ] Export E57 และ PDF แบบพร้อมพิมพ์ (กรอบแบบ, title block)
- [ ] สร้าง mesh พื้นผิว, วัดระยะจาก point cloud ในหน้า 3D
- [ ] ปรับเทียบสเกลด้วยระยะอ้างอิงที่วัดจริง (เช่น เลเซอร์) เพื่อลดความคลาดเคลื่อน
- [ ] สำรองข้อมูล/ซิงก์ขึ้น cloud และแชร์โปรเจกต์ในทีม
