#import "OfflineFace.h"
#import <React/RCTUtils.h>

#import "OfflineFace-Swift.h"

@implementation OfflineFace

- (void)registerFace:(NSDictionary *)options
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  dispatch_async(dispatch_get_main_queue(), ^{
    UIViewController *presenter = RCTPresentedViewController();
    if (presenter == nil) {
      resolve(@{@"success" : @NO, @"error" : @"camera_unavailable"});
      return;
    }
    [OfflineFaceBridge registerFaceWithOptions:options
                                    presenter:presenter
                                   completion:^(NSDictionary<NSString *, id> *result) {
                                     resolve(result);
                                   }];
  });
}

- (void)verifyFace:(NSDictionary *)options
           resolve:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject
{
  dispatch_async(dispatch_get_main_queue(), ^{
    UIViewController *presenter = RCTPresentedViewController();
    if (presenter == nil) {
      resolve(@{@"success" : @NO, @"error" : @"camera_unavailable"});
      return;
    }
    [OfflineFaceBridge verifyFaceWithOptions:options
                                  presenter:presenter
                                 completion:^(NSDictionary<NSString *, id> *result) {
                                   resolve(result);
                                 }];
  });
}

- (void)hasFaceTemplate:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [OfflineFaceBridge hasFaceTemplateWithCompletion:^(BOOL hasTemplate) {
    resolve(@(hasTemplate));
  }];
}

- (void)deleteFaceTemplate:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [OfflineFaceBridge deleteFaceTemplateWithCompletion:^{
    resolve(nil);
  }];
}

- (void)getFaceCapabilities:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [OfflineFaceBridge capabilitiesWithCompletion:^(NSDictionary<NSString *, id> *result) {
    resolve(result);
  }];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeOfflineFaceSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"OfflineFace";
}

@end
