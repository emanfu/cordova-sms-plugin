#import "Sms.h"

@implementation Sms
@synthesize callbackID;

- (void)send:(CDVInvokedUrlCommand*)command {
    [self.commandDelegate runInBackground:^{
        self.callbackID = command.callbackId;
        
        if(![MFMessageComposeViewController canSendText]) {
            UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"Notice"
                                                            message:@"SMS Text not available."
                                                           delegate:self
                                                  cancelButtonTitle:@"OK"
                                                  otherButtonTitles:nil
                                  ];

            dispatch_async(dispatch_get_main_queue(), ^{
                [alert show];
            });
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
        
        if([MFMessageComposeViewController respondsToSelector:@selector(canSendAttachments)] && [MFMessageComposeViewController canSendAttachments]) {

            NSMutableArray* attachments = [command.arguments objectAtIndex:4];
            if (attachments != nil) {
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
        
        dispatch_async(dispatch_get_main_queue(), ^{
            [self.viewController presentViewController:composeViewController animated:YES completion:nil];
        });
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
    
    [self.viewController dismissViewControllerAnimated:YES completion:nil];
    
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
}

@end
