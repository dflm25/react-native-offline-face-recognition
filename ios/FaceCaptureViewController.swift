import AVFoundation
import CoreVideo
import CoreImage
import UIKit
import Vision

/// Full-screen camera experience. Camera frames stay in memory; only LBP/HOG
/// descriptors are compared or persisted.
final class FaceCaptureViewController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {
  private let mode: String
  private let options: FaceCaptureOptions
  private let completion: ([String: Any]) -> Void
  private let store = FaceTemplateStore()
  private let extractor = FaceFeatureExtractor()
  private let glassesDetector = GlassesDetector()
  private let session = AVCaptureSession()
  private let sessionQueue = DispatchQueue(label: "com.offlineface.session")
  private let captureQueue = DispatchQueue(label: "com.offlineface.capture", qos: .userInitiated)
  private let livenessTracker: LivenessTracker?

  private let previewImageView = UIImageView()
  private let previewContext = CIContext(options: [.cacheIntermediates: false])
  private var previewContainer: UIView!
  private var overlay: FaceOvalOverlayView!
  private var instructionLabel: UILabel!
  private var progressView: UIProgressView!
  private var bottomPanel: UIView!
  private var glassesWarningLabel: UILabel!
  private var topStack: UIStackView!
  private var modelStatusLabel: UILabel?
  private var timeoutWorkItem: DispatchWorkItem?
  private var enrolledTemplate: FaceTemplate?
  private var probes: [[Float]] = []
  private let inferenceQueue = DispatchQueue(label: "com.offlineface.inference", qos: .userInitiated)
  private var inferenceInFlight = false
  private var captureGeneration = 0
  private lazy var registrationFormat = FaceDescriptorFormat.registrationFormat
  private var activeFormat: FaceDescriptorFormat { enrolledTemplate?.format ?? registrationFormat }
  private var registrationPhoto: String?
  private var lastLivenessState: LivenessTracker.State?
  private let approachTracker = FaceApproachTracker()
  private var processingFrame = false
  private var lastAnalyzedAt: CFTimeInterval = 0
  private var lastPreviewAt: CFTimeInterval = 0
  private var lastGlassesCheck: CFTimeInterval = 0
  private var glassesDetected = false
  private var blockedByGlasses = false
  private var lastProbeAt: CFTimeInterval = 0
  private var stableSince: CFTimeInterval = 0
  private var noFaceSince: CFTimeInterval = 0
  private var lastLivenessResetAt: CFTimeInterval = 0
  private var sawAnyFace = false
  private var sessionStartRequested = false
  private var timeoutScheduled = false
  private var sawUsableFace = false
  private let stateLock = NSLock()
  private var finished = false

  init(mode: String, options: FaceCaptureOptions, completion: @escaping ([String: Any]) -> Void) {
    self.mode = mode
    self.options = options
    self.completion = completion
    self.livenessTracker =
      options.requireLiveness
      ? LivenessTracker(timeoutMs: min(35_000, max(10_000, options.timeoutMs - 5_000)))
      : nil
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .fullScreen
    modalPresentationCapturesStatusBarAppearance = true
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
  }

  override var preferredStatusBarStyle: UIStatusBarStyle {
    return .lightContent
  }

  override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
    return .portrait
  }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .black

    if mode == "verify" {
      enrolledTemplate = store.load()
      if enrolledTemplate == nil {
        DispatchQueue.main.async { [weak self] in
          self?.finish(FaceCaptureError.noTemplate.result)
        }
        return
      }
    }

    buildUi()
    prepareModelStatus()
    configureSession()

    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appWillResignActive),
      name: UIApplication.willResignActiveNotification,
      object: nil
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(sessionRuntimeError(_:)),
      name: .AVCaptureSessionRuntimeError,
      object: session
    )
  }

  override func viewWillAppear(_ animated: Bool) {
    super.viewWillAppear(animated)
    startSessionIfNeeded()
    scheduleTimeoutIfNeeded()
  }

  override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)
    startSessionIfNeeded()
    updateUi("Center your face in the oval", progress: 0)
  }

  override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)
    stopSession()
    timeoutWorkItem?.cancel()
  }

  @objc private func appWillResignActive() {
    finish(FaceCaptureError.cancelled.result)
  }

  @objc private func sessionRuntimeError(_ notification: Notification) {
    finish(FaceCaptureError.cameraUnavailable.result)
  }

  @objc private func cancelTapped() {
    finish(FaceCaptureError.cancelled.result)
  }

  private func buildUi() {
    previewContainer = UIView()
    previewContainer.translatesAutoresizingMaskIntoConstraints = false
    previewContainer.backgroundColor = .black
    previewContainer.clipsToBounds = true
    view.addSubview(previewContainer)
    previewImageView.translatesAutoresizingMaskIntoConstraints = false
    previewImageView.contentMode = .scaleAspectFill
    previewImageView.clipsToBounds = true
    previewContainer.addSubview(previewImageView)
    NSLayoutConstraint.activate([
      previewImageView.leadingAnchor.constraint(equalTo: previewContainer.leadingAnchor),
      previewImageView.trailingAnchor.constraint(equalTo: previewContainer.trailingAnchor),
      previewImageView.topAnchor.constraint(equalTo: previewContainer.topAnchor),
      previewImageView.bottomAnchor.constraint(equalTo: previewContainer.bottomAnchor),
    ])

    overlay = FaceOvalOverlayView()
    overlay.translatesAutoresizingMaskIntoConstraints = false
    overlay.isUserInteractionEnabled = false
    overlay.isOpaque = false
    overlay.backgroundColor = .clear
    view.addSubview(overlay)

    let title = UILabel()
    title.text = mode == "register" ? "Register your face" : "Verify your face"
    title.font = .systemFont(ofSize: 22, weight: .semibold)
    title.textColor = .white
    title.textAlignment = .center

    let privacy = UILabel()
    privacy.text = "On-device only  •  Nothing is uploaded"
    privacy.font = .systemFont(ofSize: 12, weight: .regular)
    privacy.textColor = UIColor(red: 0.61, green: 0.72, blue: 0.67, alpha: 1)
    privacy.textAlignment = .center

    topStack = UIStackView(arrangedSubviews: [title, privacy])
    topStack.axis = .vertical
    topStack.spacing = 6
    topStack.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(topStack)

    if activeFormat == .faceApi || registrationFormat == .faceApi {
      let usesModel = activeFormat == .faceApi
      let status = UILabel()
      status.text = usesModel ? "FaceRecognitionNet · Loading" : "Previous enrollment · Texture matching"
      status.font = .systemFont(ofSize: 15, weight: .semibold)
      status.textColor = usesModel ? .systemGreen : .systemOrange
      status.textAlignment = .center
      status.numberOfLines = 0
      topStack.addArrangedSubview(status)
      modelStatusLabel = status
    }

    glassesWarningLabel = UILabel()
    glassesWarningLabel.translatesAutoresizingMaskIntoConstraints = false
    glassesWarningLabel.text = "  Remove your glasses for a better scan  "
    glassesWarningLabel.font = .systemFont(ofSize: 13, weight: .semibold)
    glassesWarningLabel.textColor = .systemOrange
    glassesWarningLabel.backgroundColor = UIColor.black.withAlphaComponent(0.62)
    glassesWarningLabel.layer.cornerRadius = 16
    glassesWarningLabel.layer.cornerCurve = .continuous
    glassesWarningLabel.clipsToBounds = true
    glassesWarningLabel.textAlignment = .center
    glassesWarningLabel.isHidden = true
    glassesWarningLabel.accessibilityLabel = "Remove your glasses for a better scan"
    view.addSubview(glassesWarningLabel)

    bottomPanel = UIView()
    bottomPanel.translatesAutoresizingMaskIntoConstraints = false
    bottomPanel.backgroundColor = .clear
    bottomPanel.layer.cornerRadius = 24
    bottomPanel.layer.cornerCurve = .continuous
    bottomPanel.layer.borderWidth = 0.5
    bottomPanel.layer.borderColor = UIColor.white.withAlphaComponent(0.12).cgColor
    bottomPanel.clipsToBounds = true
    view.addSubview(bottomPanel)

    let blur = UIVisualEffectView(effect: UIBlurEffect(style: .systemThinMaterialDark))
    blur.translatesAutoresizingMaskIntoConstraints = false
    blur.isUserInteractionEnabled = false
    bottomPanel.addSubview(blur)
    NSLayoutConstraint.activate([
      blur.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor),
      blur.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor),
      blur.topAnchor.constraint(equalTo: bottomPanel.topAnchor),
      blur.bottomAnchor.constraint(equalTo: bottomPanel.bottomAnchor),
    ])

    instructionLabel = UILabel()
    instructionLabel.text = "Center your face in the oval"
    instructionLabel.font = .systemFont(ofSize: 18, weight: .medium)
    instructionLabel.textColor = .white
    instructionLabel.textAlignment = .center
    instructionLabel.numberOfLines = 2
    instructionLabel.backgroundColor = UIColor.black.withAlphaComponent(0.72)
    instructionLabel.layer.cornerRadius = 18
    instructionLabel.clipsToBounds = true
    instructionLabel.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(instructionLabel)

    progressView = UIProgressView(progressViewStyle: .default)
    progressView.progressTintColor = .systemGreen
    progressView.trackTintColor = UIColor.white.withAlphaComponent(0.16)
    progressView.translatesAutoresizingMaskIntoConstraints = false
    progressView.layer.cornerRadius = 2
    progressView.clipsToBounds = true
    bottomPanel.addSubview(progressView)

    let cancel = UIButton(type: .system)
    cancel.setTitle("Cancel", for: .normal)
    cancel.setTitleColor(UIColor.white.withAlphaComponent(0.68), for: .normal)
    cancel.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
    cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
    cancel.translatesAutoresizingMaskIntoConstraints = false
    bottomPanel.addSubview(cancel)

    NSLayoutConstraint.activate([
      previewContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      previewContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      previewContainer.topAnchor.constraint(equalTo: view.topAnchor),
      previewContainer.bottomAnchor.constraint(equalTo: view.bottomAnchor),

      overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      overlay.topAnchor.constraint(equalTo: view.topAnchor),
      overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),

      topStack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 14),
      topStack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
      topStack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),

      bottomPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
      bottomPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
      bottomPanel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),

      glassesWarningLabel.bottomAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: -10),
      glassesWarningLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
      glassesWarningLabel.heightAnchor.constraint(equalToConstant: 32),
      glassesWarningLabel.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 20),
      glassesWarningLabel.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -20),

      instructionLabel.topAnchor.constraint(equalTo: topStack.bottomAnchor, constant: 12),
      instructionLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 28),
      instructionLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -28),
      instructionLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),

      progressView.topAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: 16),
      progressView.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 20),
      progressView.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -20),
      progressView.heightAnchor.constraint(equalToConstant: 3),

      cancel.topAnchor.constraint(equalTo: progressView.bottomAnchor, constant: 8),
      cancel.centerXAnchor.constraint(equalTo: bottomPanel.centerXAnchor),
      cancel.heightAnchor.constraint(equalToConstant: 40),
      cancel.bottomAnchor.constraint(equalTo: bottomPanel.bottomAnchor, constant: -8),
    ])
  }

  private func prepareModelStatus() {
    guard activeFormat == .faceApi else { return }
    inferenceQueue.async { [weak self] in
      let available: Bool
      do {
        try FaceApiEmbedding.prepare()
        available = true
      } catch {
        available = false
      }
      DispatchQueue.main.async { [weak self] in
        guard let self, !self.isFinished() else { return }
        self.modelStatusLabel?.text = available
          ? "FaceRecognitionNet · Core ML ready"
          : "FaceRecognitionNet · Unavailable"
        self.modelStatusLabel?.textColor = available ? .systemGreen : .systemOrange
      }
    }
  }

  private func startSessionIfNeeded() {
    guard !sessionStartRequested else { return }
    sessionStartRequested = true
    sessionQueue.async { [weak self] in
      guard let self else { return }
      if !self.session.isRunning {
        self.session.startRunning()
      }
    }
  }

  private func scheduleTimeoutIfNeeded() {
    guard !timeoutScheduled else { return }
    timeoutScheduled = true
    let workItem = DispatchWorkItem { [weak self] in
      guard let self else { return }
      let error: FaceCaptureError =
        !self.sawAnyFace ? .noFace : (!self.sawUsableFace ? .faceTooSmall : .timeout)
      self.finish(error.result)
    }
    timeoutWorkItem = workItem
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(options.timeoutMs), execute: workItem)
  }

  private func configureSession() {
    session.beginConfiguration()
    session.sessionPreset = .vga640x480

    let frontCamera =
      AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
      ?? AVCaptureDevice.default(.builtInTrueDepthCamera, for: .video, position: .front)
    guard
      let device = frontCamera,
      let input = try? AVCaptureDeviceInput(device: device),
      session.canAddInput(input)
    else {
      session.commitConfiguration()
      DispatchQueue.main.async { [weak self] in
        self?.finish(FaceCaptureError.cameraUnavailable.result)
      }
      return
    }
    session.addInput(input)

    let output = AVCaptureVideoDataOutput()
    output.alwaysDiscardsLateVideoFrames = true
    output.videoSettings = [
      kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
    ]
    output.setSampleBufferDelegate(self, queue: captureQueue)
    guard session.canAddOutput(output) else {
      session.commitConfiguration()
      DispatchQueue.main.async { [weak self] in
        self?.finish(FaceCaptureError.cameraUnavailable.result)
      }
      return
    }
    session.addOutput(output)

    if let connection = output.connection(with: .video) {
      if connection.isVideoOrientationSupported {
        connection.videoOrientation = .portrait
      }
      if connection.isVideoMirroringSupported {
        connection.automaticallyAdjustsVideoMirroring = false
        connection.isVideoMirrored = true
      }
    }
    session.commitConfiguration()

  }

  private func previewImage(from pixelBuffer: CVPixelBuffer) -> UIImage? {
    let image = CIImage(cvPixelBuffer: pixelBuffer)
    guard let cgImage = previewContext.createCGImage(image, from: image.extent) else {
      return nil
    }
    return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
  }

  private func stopSession() {
    sessionQueue.async { [weak self] in
      guard let self else { return }
      if self.session.isRunning {
        self.session.stopRunning()
      }
    }
  }

  func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    guard !isFinished(), let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
      return
    }
    let now = CACurrentMediaTime()
    if now - lastPreviewAt >= 0.07 {
      lastPreviewAt = now
      if let image = previewImage(from: pixelBuffer) {
        DispatchQueue.main.async { [weak self] in
          self?.previewImageView.image = image
        }
      }
    }

    guard !processingFrame, now - lastAnalyzedAt >= 0.07 else {
      return
    }
    processingFrame = true
    lastAnalyzedAt = now

    let request = VNDetectFaceLandmarksRequest()
    let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
    do {
      try handler.perform([request])
    } catch {
      processingFrame = false
      return
    }

    let faces = request.results ?? []
    var glasses: Bool?
    if !options.allowGlasses, faces.count == 1,
      now - lastGlassesCheck >= 0.9,
      let face = faces.first
    {
      lastGlassesCheck = now
      glasses = glassesDetector.detect(
        pixelBuffer: pixelBuffer,
        face: face,
        sensitivity: options.glassesSensitivity
      )
    }

    let safeBuffer = copyPixelBuffer(pixelBuffer)
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      if let safeBuffer {
        self.handle(faces: faces, pixelBuffer: safeBuffer, glasses: glasses)
      }
      self.captureQueue.async { [weak self] in
        self?.processingFrame = false
      }
    }
  }

  private func copyPixelBuffer(_ source: CVPixelBuffer) -> CVPixelBuffer? {
    let width = CVPixelBufferGetWidth(source)
    let height = CVPixelBufferGetHeight(source)
    let format = CVPixelBufferGetPixelFormatType(source)
    var destination: CVPixelBuffer?
    let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary
    guard
      CVPixelBufferCreate(kCFAllocatorDefault, width, height, format, attributes, &destination)
        == kCVReturnSuccess,
      let destination
    else {
      return nil
    }

    CVPixelBufferLockBaseAddress(source, .readOnly)
    CVPixelBufferLockBaseAddress(destination, [])
    defer {
      CVPixelBufferUnlockBaseAddress(destination, [])
      CVPixelBufferUnlockBaseAddress(source, .readOnly)
    }
    guard
      let sourceBase = CVPixelBufferGetBaseAddress(source),
      let destinationBase = CVPixelBufferGetBaseAddress(destination)
    else {
      return nil
    }
    let sourceStride = CVPixelBufferGetBytesPerRow(source)
    let destinationStride = CVPixelBufferGetBytesPerRow(destination)
    let rowBytes = min(sourceStride, destinationStride)
    for row in 0..<height {
      memcpy(destinationBase.advanced(by: row * destinationStride),
             sourceBase.advanced(by: row * sourceStride),
             rowBytes)
    }
    return destination
  }

  private func handle(
    faces: [VNFaceObservation],
    pixelBuffer: CVPixelBuffer,
    glasses: Bool?
  ) {
    guard !isFinished() else { return }
    let now = CACurrentMediaTime()

    if let glasses {
      glassesDetected = glasses
      setGlassesWarning(glasses, blocking: !options.allowGlasses)
    }

    if faces.isEmpty {
      stableSince = 0
      glassesDetected = false
      setGlassesWarning(false, blocking: !options.allowGlasses)
      if noFaceSince == 0 {
        noFaceSince = now
      }
      if now - noFaceSince > 1.2, now - lastLivenessResetAt > 0.8 {
        resetLiveness(now: now)
        lastLivenessResetAt = now
      }
      if now - noFaceSince > 0.6 {
        updateUi("Center your face in the oval", progress: livenessTracker?.update(
          now: now, yaw: nil, mouthOpenRatio: nil
        ).progress ?? 0)
      }
      return
    }

    sawAnyFace = true
    if faces.count > 1 {
      glassesDetected = false
      setGlassesWarning(false, blocking: !options.allowGlasses)
      noFaceSince = 0
      if now - lastLivenessResetAt > 0.8 {
        resetLiveness(now: now)
        lastLivenessResetAt = now
      }
      updateUi("Only one person should be in frame", progress: 0)
      return
    }

    noFaceSince = 0
    guard let face = faces.max(by: { lhs, rhs in
      lhs.boundingBox.width * lhs.boundingBox.height < rhs.boundingBox.width * rhs.boundingBox.height
    }) else {
      return
    }

    let box = face.boundingBox
    if box.width < 0.20 || box.height < 0.20 {
      stableSince = 0
      updateUi("Move a little closer", progress: currentLivenessProgress())
      return
    }
    if box.width > 0.75 || box.height > 0.85 {
      stableSince = 0
      updateUi("Move a little farther away", progress: currentLivenessProgress())
      return
    }
    if box.midX < 0.18 || box.midX > 0.82 || box.midY < 0.12 || box.midY > 0.88 {
      stableSince = 0
      updateUi("Center your face in the oval", progress: currentLivenessProgress())
      return
    }
    sawUsableFace = true

    if !options.allowGlasses && glassesDetected {
      if !blockedByGlasses {
        blockedByGlasses = true
        probes.removeAll()
        stableSince = 0
      }
      updateUi("Remove your glasses to continue", progress: 0)
      setGlassesWarning(true, blocking: true)
      return
    }
    if blockedByGlasses {
      blockedByGlasses = false
      resetLiveness(now: now)
    }

    if let tracker = livenessTracker {
      if !approachTracker.completed {
        guard approachTracker.update(width: box.width) else {
          updateUi(approachTracker.needsStartingDistance ? "Move a little farther away" : "Slowly move closer", progress: 0.2)
          return
        }
        tracker.restart(now: now)
      }
      let yaw = estimatedYaw(face: face)
      let mouth = mouthOpenRatio(face: face)
      let state = tracker.update(now: now, yaw: yaw, mouthOpenRatio: mouth)
      lastLivenessState = state
      if state.failed {
        finish(faceResult(success: false, livenessPassed: false, error: .livenessFailed))
        return
      }
      if !state.passed {
        updateUi(state.prompt, progress: state.progress)
      }
      if state.passed {
        guard let yaw, yaw.isFinite, abs(yaw) <= 0.16 else {
          stableSince = 0
          updateUi("Look straight ahead", progress: 1)
          return
        }
        if stableSince == 0 {
          stableSince = now
        }
        if now - stableSince < 0.25 {
          updateUi("Hold still…", progress: 1)
          return
        }
        captureProbe(pixelBuffer: pixelBuffer, face: face, now: now)
      }
    } else {
      guard let yaw = estimatedYaw(face: face), yaw.isFinite, abs(yaw) <= 0.16 else {
        stableSince = 0
        updateUi("Look straight ahead", progress: 0)
        return
      }
      if stableSince == 0 {
        stableSince = now
      }
      if now - stableSince < 0.3 {
        updateUi("Hold still…", progress: 0)
        return
      }
      captureProbe(pixelBuffer: pixelBuffer, face: face, now: now)
    }
  }

  private func captureProbe(
    pixelBuffer: CVPixelBuffer,
    face: VNFaceObservation,
    now: CFTimeInterval
  ) {
    guard now - lastProbeAt >= 0.15 else {
      return
    }
    let format = activeFormat
    if format == .faceApi {
      guard !inferenceInFlight else { return }
      inferenceInFlight = true
      let generation = captureGeneration
      inferenceQueue.async { [weak self] in
        guard let self, !self.isFinished() else { return }
        let result = self.extractor.extract(pixelBuffer: pixelBuffer, face: face, format: format)
        DispatchQueue.main.async {
          self.inferenceInFlight = false
          guard !self.isFinished(), self.captureGeneration == generation else { return }
          self.acceptProbe(result, format: format, pixelBuffer: pixelBuffer, face: face, now: now)
        }
      }
      return
    }
    let result = extractor.extract(pixelBuffer: pixelBuffer, face: face, format: format)
    acceptProbe(result, format: format, pixelBuffer: pixelBuffer, face: face, now: now)
  }

  private func acceptProbe(
    _ result: Result<[Float], FaceFeatureExtractor.CaptureError>,
    format: FaceDescriptorFormat,
    pixelBuffer: CVPixelBuffer,
    face: VNFaceObservation,
    now: CFTimeInterval
  ) {
    let descriptor: [Float]
    switch result {
    case .success(let sample):
      descriptor = sample
    case .failure(.poorLighting):
      updateUi("Face an even light source", progress: currentLivenessProgress())
      return
    case .failure(.modelUnavailable):
      finish(FaceCaptureError.notSupported.result)
      return
    case .failure(.invalidFrame):
      updateUi(
        format == .legacy ? "Improve lighting and hold still…" : "Keep both eyes visible in even light",
        progress: currentLivenessProgress()
      )
      return
    }

    lastProbeAt = now
    probes.append(descriptor)
    if format == .faceApi {
      modelStatusLabel?.text = "FaceRecognitionNet · \(probes.count)/\(Self.probeCount) samples"
    }
    updateUi(
      "Capturing \(probes.count)/\(FaceCaptureViewController.probeCount)",
      progress: 0.9 + 0.1 * Float(probes.count) / Float(FaceCaptureViewController.probeCount)
    )

    if probes.count >= FaceCaptureViewController.probeCount {
      if mode == "register", options.returnPhoto {
        guard let photo = makePhoto(pixelBuffer: pixelBuffer, face: face) else {
          finish(FaceCaptureError.photoCaptureFailed.result)
          return
        }
        registrationPhoto = photo
      }
      completeCapture()
    }
  }

  private func completeCapture() {
    guard !isFinished() else { return }

    if mode == "register" {
      do {
        let template = try store.save(descriptors: probes, format: registrationFormat)
        finish(
          faceResult(
            success: true,
            templateId: template.templateId,
            photoBase64: registrationPhoto,
            livenessPassed: lastLivenessState?.passed
          )
        )
      } catch {
        finish(FaceCaptureError.storageError.result)
      }
      return
    }

    guard let enrolled = enrolledTemplate else {
      finish(FaceCaptureError.noTemplate.result)
      return
    }
    let score: Double
    let matched: Bool
    if enrolled.format == .faceApi {
      guard let distance = FaceApiMatcher.meanDistance(template: enrolled.descriptors, probes: probes) else {
        finish(FaceCaptureError.unknownError.result)
        return
      }
      score = max(0, 1 - distance)
      matched = distance < FaceApiMatcher.maximumDistance
    } else {
      score = FaceMatcher.score(template: enrolled.descriptors, probes: probes)
      matched = score >= options.threshold
    }
    let livenessPassed = options.requireLiveness ? (lastLivenessState?.passed ?? false) : nil
    if matched {
      finish(faceResult(success: true, score: score, livenessPassed: livenessPassed))
    } else {
      finish(
        faceResult(
          success: false,
          score: score,
          livenessPassed: livenessPassed,
          error: .faceMismatch
        )
      )
    }
  }

  private func resetLiveness(now: CFTimeInterval) {
    captureGeneration += 1
    if activeFormat == .faceApi, !probes.isEmpty {
      modelStatusLabel?.text = "FaceRecognitionNet · Core ML ready"
    }
    registrationPhoto = nil
    approachTracker.reset()
    probes.removeAll()
    lastLivenessState = nil
    stableSince = 0
    livenessTracker?.restart(now: now)
  }

  private func makePhoto(pixelBuffer: CVPixelBuffer, face: VNFaceObservation) -> String? {
    let image = CIImage(cvPixelBuffer: pixelBuffer)
    let bounds = image.extent
    let box = face.boundingBox
    let rect = CGRect(x: box.minX * bounds.width, y: box.minY * bounds.height,
                      width: box.width * bounds.width, height: box.height * bounds.height)
    let side = min(max(rect.width, rect.height) * 1.35, min(bounds.width, bounds.height))
    guard side >= 24 else { return nil }
    let crop = CGRect(x: min(max(rect.midX - side / 2, bounds.minX), bounds.maxX - side),
                      y: min(max(rect.midY - side / 2, bounds.minY), bounds.maxY - side),
                      width: side, height: side)
    guard let cgImage = CIContext(options: [.cacheIntermediates: false]).createCGImage(image, from: crop) else { return nil }
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: format)
    let jpeg = renderer.jpegData(withCompressionQuality: 0.65) { _ in
      UIImage(cgImage: cgImage).draw(in: CGRect(x: 0, y: 0, width: 256, height: 256))
    }
    guard jpeg.count <= 96_000 else { return nil }
    return jpeg.base64EncodedString()
  }

  private func estimatedYaw(face: VNFaceObservation) -> Float? {
    // Zero is a valid, centered pose. Falling back to landmark geometry for
    // small values can make an already frontal face appear turned.
    if let yaw = face.yaw?.floatValue, yaw.isFinite {
      return yaw
    }
    guard let landmarks = face.landmarks,
      let leftEye = landmarks.leftEye?.normalizedPoints,
      let rightEye = landmarks.rightEye?.normalizedPoints,
      let nose = landmarks.nose?.normalizedPoints,
      !leftEye.isEmpty,
      !rightEye.isEmpty,
      !nose.isEmpty
    else {
      return nil
    }

    let left = averagePoint(leftEye)
    let right = averagePoint(rightEye)
    let nosePoint = averagePoint(nose)
    let interocular = abs(right.x - left.x)
    guard interocular > 0.01 else {
      return nil
    }
    let offset = (nosePoint.x - (left.x + right.x) / 2) / interocular
    let clamped = min(0.95, max(-0.95, offset))
    return asin(Float(clamped))
  }

  private func mouthOpenRatio(face: VNFaceObservation) -> Float? {
    guard let landmarks = face.landmarks else {
      return nil
    }
    let inner = landmarks.innerLips?.normalizedPoints
    let points = (inner?.count ?? 0) >= 4
      ? inner
      : landmarks.outerLips?.normalizedPoints
    guard let points, points.count >= 4 else {
      return nil
    }

    var minX = CGFloat.greatestFiniteMagnitude
    var maxX = -CGFloat.greatestFiniteMagnitude
    var minY = CGFloat.greatestFiniteMagnitude
    var maxY = -CGFloat.greatestFiniteMagnitude
    for point in points {
      minX = min(minX, point.x)
      maxX = max(maxX, point.x)
      minY = min(minY, point.y)
      maxY = max(maxY, point.y)
    }
    let width = maxX - minX
    guard width > 1e-5 else {
      return nil
    }
    return Float((maxY - minY) / width)
  }

  private func averagePoint(_ points: [CGPoint]) -> CGPoint {
    var x: CGFloat = 0
    var y: CGFloat = 0
    for point in points {
      x += point.x
      y += point.y
    }
    return CGPoint(x: x / CGFloat(points.count), y: y / CGFloat(points.count))
  }

  private func currentLivenessProgress() -> Float {
    return lastLivenessState?.progress ?? 0
  }

  private func setGlassesWarning(_ detected: Bool, blocking: Bool) {
    glassesWarningLabel.text =
      blocking
      ? "  Remove your glasses to continue  "
      : "  Remove your glasses for a better scan  "
    let shouldHide = !detected
    guard glassesWarningLabel.isHidden != shouldHide else { return }
    UIView.transition(
      with: glassesWarningLabel,
      duration: 0.2,
      options: [.transitionCrossDissolve]
    ) {
      self.glassesWarningLabel.isHidden = shouldHide
    }
  }

  private func updateUi(_ prompt: String, progress: Float) {
    guard !isFinished() else { return }
    instructionLabel.text = prompt
    progressView.setProgress(progress.clamped(to: 0...1), animated: true)
    overlay.progress = CGFloat(progress.clamped(to: 0...1))
  }

  private func isFinished() -> Bool {
    stateLock.lock()
    defer { stateLock.unlock() }
    return finished
  }

  private func markFinished() -> Bool {
    stateLock.lock()
    defer { stateLock.unlock() }
    if finished {
      return false
    }
    finished = true
    return true
  }

  private func finish(_ result: [String: Any]) {
    guard markFinished() else { return }
    timeoutWorkItem?.cancel()
    stopSession()

    if let presenter = presentingViewController {
      presenter.dismiss(animated: true) { [completion] in
        completion(result)
      }
    } else if isBeingPresented {
      dismiss(animated: false) { [completion] in
        completion(result)
      }
    } else {
      completion(result)
    }
  }

  private static let probeCount = 6
}

private extension Comparable {
  func clamped(to limits: ClosedRange<Self>) -> Self {
    return min(max(self, limits.lowerBound), limits.upperBound)
  }
}

private final class FaceOvalOverlayView: UIView {
  var progress: CGFloat = 0 {
    didSet {
      setNeedsDisplay()
    }
  }

  override func draw(_ rect: CGRect) {
    guard let context = UIGraphicsGetCurrentContext() else { return }
    let ovalWidth = min(bounds.width * 0.74, bounds.height * 0.44)
    let ovalHeight = ovalWidth * 1.28
    let center = CGPoint(x: bounds.midX, y: bounds.height * 0.43)
    let ovalRect = CGRect(
      x: center.x - ovalWidth / 2,
      y: center.y - ovalHeight / 2,
      width: ovalWidth,
      height: ovalHeight
    )
    let oval = UIBezierPath(ovalIn: ovalRect)

    // Dim everything outside the camera oval, keeping the face preview visible.
    let scrim = UIBezierPath(rect: bounds)
    scrim.append(oval)
    scrim.usesEvenOddFillRule = true
    UIColor.black.withAlphaComponent(0.52).setFill()
    scrim.fill()

    // The face guide and its progress ring share the same elliptical geometry,
    // so the green progress never drifts away from the visible oval.
    UIColor.white.withAlphaComponent(0.56).setStroke()
    oval.lineWidth = 1.5
    oval.stroke()

    let ringRect = ovalRect.insetBy(dx: -6, dy: -6)
    let track = ellipsePath(in: ringRect)
    context.saveGState()
    context.setStrokeColor(UIColor.white.withAlphaComponent(0.16).cgColor)
    context.setLineWidth(3.5)
    context.setLineCap(.round)
    context.addPath(track)
    context.strokePath()
    context.restoreGState()

    guard progress > 0.001 else { return }
    let progressPath = ellipsePath(
      in: ringRect,
      startAngle: -.pi / 2,
      endAngle: -.pi / 2 + 2 * .pi * progress
    )
    context.saveGState()
    context.setStrokeColor(UIColor.systemGreen.cgColor)
    context.setLineWidth(3.5)
    context.setLineCap(.round)
    context.setShadow(
      offset: .zero,
      blur: 9,
      color: UIColor.systemGreen.withAlphaComponent(0.55).cgColor
    )
    context.addPath(progressPath)
    context.strokePath()
    context.restoreGState()
  }

  private func ellipsePath(
    in rect: CGRect,
    startAngle: CGFloat = -.pi / 2,
    endAngle: CGFloat = 1.5 * .pi
  ) -> CGPath {
    let path = CGMutablePath()
    let steps = 180
    let rx = rect.width / 2
    let ry = rect.height / 2
    for index in 0...steps {
      let angle = startAngle + (endAngle - startAngle) * CGFloat(index) / CGFloat(steps)
      let point = CGPoint(
        x: rect.midX + cos(angle) * rx,
        y: rect.midY + sin(angle) * ry
      )
      if index == 0 {
        path.move(to: point)
      } else {
        path.addLine(to: point)
      }
    }
    return path
  }
}
