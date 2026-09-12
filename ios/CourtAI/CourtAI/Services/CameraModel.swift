import AVFoundation
import UIKit
import SwiftUI

final class CameraModel: NSObject, ObservableObject {
    @Published var ball: BallPoint?
    @Published var permissionDenied = false
    @Published var usingFront = true

    let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "courtai.camera")
    private var isRunning = false

    func start(front: Bool = true) {
        usingFront = front
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    if ok { self.configure() } else { self.permissionDenied = true }
                }
            }
        default:
            permissionDenied = true
        }
    }

    func flip() {
        stop()
        start(front: !usingFront)
    }

    func stop() {
        queue.async {
            if self.session.isRunning { self.session.stopRunning() }
            self.isRunning = false
        }
    }

    private func configure() {
        queue.async {
            self.session.beginConfiguration()
            self.session.sessionPreset = .hd1280x720
            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.outputs.forEach { self.session.removeOutput($0) }

            let position: AVCaptureDevice.Position = self.usingFront ? .front : .back
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
                  let input = try? AVCaptureDeviceInput(device: device),
                  self.session.canAddInput(input) else {
                self.session.commitConfiguration()
                return
            }
            self.session.addInput(input)
            self.output.alwaysDiscardsLateVideoFrames = true
            self.output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ]
            self.output.setSampleBufferDelegate(self, queue: self.queue)
            if self.session.canAddOutput(self.output) {
                self.session.addOutput(self.output)
            }
            if let conn = self.output.connection(with: .video) {
                if conn.isVideoOrientationSupported {
                    conn.videoOrientation = .portrait
                }
                if self.usingFront && conn.isVideoMirroringSupported {
                    conn.isVideoMirrored = true
                }
            }
            self.session.commitConfiguration()
            if !self.session.isRunning {
                self.session.startRunning()
                self.isRunning = true
            }
        }
    }
}

extension CameraModel: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return }
        let w = CVPixelBufferGetWidth(pb)
        let h = CVPixelBufferGetHeight(pb)
        let row = CVPixelBufferGetBytesPerRow(pb)
        // BGRA -> treat as RGBA channel order carefully for orange detect
        // Our detector expects RGBA; convert by swapping R/B when reading via pointer as BGRA.
        let ptr = base.assumingMemoryBound(to: UInt8.self)
        var sumX = 0.0, sumY = 0.0, count = 0
        var minX = w, minY = h, maxX = 0, maxY = 0
        let step = max(4, min(w, h) / 80)
        var y = 0
        while y < h {
            var x = 0
            while x < w {
                let o = y * row + x * 4
                let b = Int(ptr[o]), g = Int(ptr[o + 1]), r = Int(ptr[o + 2])
                if r >= 110 && r > g + 25 && r > b + 35 && g >= 35 && g <= 180 && b <= 130 {
                    let sat = Double(r - min(g, b)) / Double(max(1, r))
                    if sat >= 0.28 {
                        sumX += Double(x); sumY += Double(y); count += 1
                        minX = min(minX, x); minY = min(minY, y)
                        maxX = max(maxX, x); maxY = max(maxY, y)
                    }
                }
                x += step
            }
            y += step
        }
        let point: BallPoint?
        if count >= 10 {
            let cx = CGFloat(sumX / Double(count) / Double(w))
            let cy = CGFloat(sumY / Double(count) / Double(h))
            let radius = max(CGFloat(maxX - minX) / CGFloat(w), CGFloat(maxY - minY) / CGFloat(h)) / 2
            if radius >= 0.01 && radius <= 0.3 {
                point = BallPoint(x: cx, y: cy, radius: radius, conf: min(1, CGFloat(count) / 120))
            } else {
                point = nil
            }
        } else {
            point = nil
        }
        DispatchQueue.main.async { self.ball = point }
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.videoPreviewLayer.session = session
        v.videoPreviewLayer.videoGravity = .resizeAspectFill
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.videoPreviewLayer.session = session
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
