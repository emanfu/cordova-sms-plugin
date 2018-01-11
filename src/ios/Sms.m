#import "Sms.h"

@implementation Sms {
    BOOL hasAttachments;
}

@synthesize callbackID;

- (void)send:(CDVInvokedUrlCommand*)command {
    [self.commandDelegate runInBackground:^{
        self.callbackID = command.callbackId;
        
        if(![MFMessageComposeViewController canSendText]) {
            NSString* errMessage = @"SMS Text not available.";
            UIAlertView *alert = [[UIAlertView alloc]initWithTitle:@"Notice"
                                                           message:errMessage
                                                           delegate:self
                                                  cancelButtonTitle:@"OK"
                                                  otherButtonTitles:nil
                                  ];

            dispatch_async(dispatch_get_main_queue(), ^{
                [alert show];
            });

            NSDictionary *resultDict = [NSDictionary dictionaryWithObjectsAndKeys:
                                        @"smsNotAvailable", @"status",
                                        errMessage, @"errorMessage", nil];
            CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                          messageAsDictionary:resultDict];
            
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackID];
            return;
        }
        
        MFMessageComposeViewController *composeViewController = [[MFMessageComposeViewController alloc] init];
        composeViewController.messageComposeDelegate = self;
        
        NSString* body = [command.arguments objectAtIndex:1];
        if (body != nil) {
            BOOL replaceLineBreaks = [[command.arguments objectAtIndex:3] boolValue];
            if (replaceLineBreaks) {
                body = [body stringByReplacingOccurrencesOfString: @"\\n" withString: @"\n"];
            }
            [composeViewController setBody:body];
        }
        
        NSMutableArray* recipients = [command.arguments objectAtIndex:0];
        if (recipients != nil) {
            if ([recipients.firstObject isEqual: @""]) {
                [recipients replaceObjectAtIndex:0 withObject:@"?"];
            }
            
            [composeViewController setRecipients:recipients];
        }

        // This is tricky. According to what I observed on iOS 11 with iPhone 6S Plus and iPhone X,
        // if composeViewController is presented before attachments are added, it has higher chance
        // that composeViewController will be all black next time when it is presented.
        [self.viewController presentViewController:composeViewController animated:YES completion:nil];

        hasAttachments = NO;
        if([MFMessageComposeViewController respondsToSelector:@selector(canSendAttachments)] && [MFMessageComposeViewController canSendAttachments]) {

            NSMutableArray* attachments = [command.arguments objectAtIndex:4];
            if (attachments != nil) {
                hasAttachments = [attachments count] > 0;
                for (int i = 0; i < [attachments count]; i++) {
                    if ([composeViewController respondsToSelector:@selector(addAttachmentURL:withAlternateFilename:)]) {
                        NSString *filename = [NSString stringWithFormat:@"picture%d.png", i];
                        NSURL *imageUrl =[NSURL URLWithString:attachments[i]];
                        [composeViewController addAttachmentURL:imageUrl
                                          withAlternateFilename:filename];
                    }
                }
            }
        }
        
//        dispatch_async(dispatch_get_main_queue(), ^{
//            [self.viewController presentViewController:composeViewController animated:YES completion:nil];
//        });
    }];
}

#pragma mark - MFMessageComposeViewControllerDelegate Implementation
// Dismisses the composition interface when users tap Cancel or Send. Proceeds to update the message field with the result of the operation.
- (void)messageComposeViewController:(MFMessageComposeViewController *)controller didFinishWithResult:(MessageComposeResult)result {
    // Notifies users about errors associated with the interface
    int webviewResult = 0;
    NSString* message = @"";
    NSString* status = @"";
    
    switch(result) {
        case MessageComposeResultCancelled:
            webviewResult = 0;
            status = @"cancelled";
            message = @"Message cancelled.";
            break;
        case MessageComposeResultSent:
            webviewResult = 1;
            status = @"sent";
            message = @"Message sent.";
            break;
        case MessageComposeResultFailed:
            webviewResult = 2;
            status = @"failed";
            message = @"Message failed.";
            break;
        default:
            webviewResult = 3;
            status = @"unknown";
            message = @"Unknown error.";
            break;
    }

    // Delay the dismission of composeViewController. For a message with attachments, delay 2 seconds;
    // for a pure text message, delay 0.5 seconds. This is critical for working around the
    // composeViewController-is-black-next-time problem.
    double delayInSeconds = webviewResult != 1 ? 0 : (hasAttachments ? 2.0 : 0.5);
    NSLog(@"Dismissing the composition view in %f seconds", delayInSeconds);
    dispatch_time_t popTime = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delayInSeconds * NSEC_PER_SEC));
    dispatch_after(popTime, dispatch_get_main_queue(), ^(void){
        NSLog(@"Dismissing the composition view");
        [self.viewController dismissViewControllerAnimated:NO completion:^{
            if(webviewResult == 1) {
                CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                                  messageAsString:message];
                
                [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackID];
            } else {
                NSDictionary *resultDict = [NSDictionary dictionaryWithObjectsAndKeys:
                                            status, @"status",
                                            message, @"errorMessage", nil];
                CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                              messageAsDictionary:resultDict];
                
                [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackID];
            }
        }];
    });
}

@end

