Self-generated synthetic fixtures; no user photos or external copyrighted media.

cover.jpg: FFmpeg color source, 32x16 JPEG.
video.mp4: FFmpeg testsrc2, 32x16 at 10 fps for one second, SDR H.264 baseline
           plus a synthetic 440 Hz AAC audio track, MP4 faststart.

Generation (FFmpeg n9.0.2 on this workstation):
ffmpeg -f lavfi -i color=c=0x307090:s=32x16 -frames:v 1 -threads 1 -update 1 cover.jpg
ffmpeg -f lavfi -i testsrc2=size=32x16:rate=10 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 1 -c:v libx264 -threads 1 -pix_fmt yuv420p -profile:v baseline -c:a aac -movflags +faststart video.mp4

The JVM tests create independent modern/legacy XMP and malformed variations from
these files. Tests do not invoke FFmpeg and have no Android or WeChat dependency.
