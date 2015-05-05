package types;

import android.app.Activity;
import android.os.Bundle;
import android.os.Parcelable;
import com.kboyarshinov.activityscreens.annotation.ActivityArg;
import com.kboyarshinov.activityscreens.annotation.ActivityScreen;
import model.ParcelableClass;

import java.util.ArrayList;

@ActivityScreen
public class ListsActivity extends Activity {
    @ActivityArg
    ArrayList<String> list1;
}