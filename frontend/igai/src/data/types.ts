export interface UserData {
  userId: string;
  userName: string;         // mapped from userPrincipalName
  userResourceName: string; // mapped from userPrincipalName (if needed)
  createdDate: string;      // mapped from activityDateTime
}