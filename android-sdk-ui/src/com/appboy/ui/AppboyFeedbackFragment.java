package com.appboy.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import com.appboy.Appboy;
import com.appboy.Constants;
import com.appboy.support.ValidationUtils;
import com.appboy.ui.support.StringUtils;

public class AppboyFeedbackFragment extends Fragment {
  private static final String TAG = String.format("%s.%s", Constants.APPBOY, AppboyFeedbackFragment.class.getName());

  /**
   * Listener to be called after the feedback has been submitted or cancelled. You must set the
   * static listener before creating an instance of the AppboyFeedbackFragment.
   */
  public interface FeedbackFinishedListener {
    void onFeedbackFinished();
  }

  private Button mCancelButton;
  private Button mSendButton;
  private CheckBox mIsBugCheckBox;
  private EditText mMessageEditText;
  private EditText mEmailEditText;
  private TextWatcher mSendButtonWatcher;
  private View.OnClickListener mCancelListener;
  private View.OnClickListener mSendListener;
  private FeedbackFinishedListener mFeedbackFinishedListener;
  private int mOriginalSoftInputMode;

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    mSendButtonWatcher = new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence sequence, int start, int count, int after) { }
      @Override public void onTextChanged(CharSequence sequence, int start, int before, int count) { }
      @Override
      public void afterTextChanged(Editable sequence) {
        ensureSendButton();
      }
    };
    mCancelListener = new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        hideSoftKeyboard();
        if (mFeedbackFinishedListener != null) {
          mFeedbackFinishedListener.onFeedbackFinished();
        }
        clearData();
      }
    };
    mSendListener = new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        hideSoftKeyboard();
        boolean isBug = mIsBugCheckBox.isChecked();
        String message = mMessageEditText.getText().toString();
        String email = mEmailEditText.getText().toString();
        boolean result = Appboy.getInstance(getActivity()).submitFeedback(email, message, isBug);
        if (!result) {
          Log.e(TAG, "Could not post feedback.");
        }
        if (mFeedbackFinishedListener != null) {
          mFeedbackFinishedListener.onFeedbackFinished();
        }
        clearData();
      }
    };
    setRetainInstance(true);
  }

  @Override
  public View onCreateView(LayoutInflater layoutInflater, ViewGroup container, Bundle savedInstanceState) {
    View view = layoutInflater.inflate(R.layout.com_appboy_feedback, container, false);
    mCancelButton = (Button) view.findViewById(R.id.com_appboy_feedback_cancel);
    mSendButton = (Button) view.findViewById(R.id.com_appboy_feedback_send);
    mIsBugCheckBox = (CheckBox) view.findViewById(R.id.com_appboy_feedback_is_bug);
    mMessageEditText = (EditText) view.findViewById(R.id.com_appboy_feedback_message);
    mEmailEditText = (EditText) view.findViewById(R.id.com_appboy_feedback_email);

    mMessageEditText.addTextChangedListener(mSendButtonWatcher);
    mEmailEditText.addTextChangedListener(mSendButtonWatcher);
    mCancelButton.setOnClickListener(mCancelListener);
    mSendButton.setOnClickListener(mSendListener);
    return view;
  }

  @Override
  public void onResume() {
    super.onResume();
    Appboy.getInstance(getActivity()).logFeedbackDisplayed();

    Activity activity = getActivity();
    Window window = activity.getWindow();

    // Overriding the soft input mode of the Window so that the Send and Cancel buttons appear above
    // the soft keyboard when either EditText field gains focus. We cache the mode in order to set it
    // back to the original value when the Fragment is paused.
    mOriginalSoftInputMode = window.getAttributes().softInputMode;
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

    Appboy.getInstance(activity).logFeedbackDisplayed();
    ensureSendButton();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    mMessageEditText.removeTextChangedListener(mSendButtonWatcher);
    mEmailEditText.removeTextChangedListener(mSendButtonWatcher);
  }

  public void setFeedbackFinishedListener(FeedbackFinishedListener feedbackFinishedListener) {
    mFeedbackFinishedListener = feedbackFinishedListener;
  }

  private boolean validatedMessage() {
    return mMessageEditText.getText() != null && !StringUtils.isNullOrBlank(mMessageEditText.getText().toString());
  }

  private boolean validatedEmail() {
    return mEmailEditText.getText() != null && !StringUtils.isNullOrBlank(mEmailEditText.getText().toString()) &&
      ValidationUtils.isValidEmailAddress(mEmailEditText.getText().toString());
  }

  private void ensureSendButton() {
    if (validatedMessage() && validatedEmail()) {
      mSendButton.setEnabled(true);
    } else {
      mSendButton.setEnabled(false);
    }
  }

  private void clearData() {
    mEmailEditText.setText(StringUtils.EMPTY_STRING);
    mMessageEditText.setText(StringUtils.EMPTY_STRING);
    mIsBugCheckBox.setChecked(false);
  }

  private void hideSoftKeyboard() {
    Activity activity = getActivity();
    activity.getWindow().setSoftInputMode(mOriginalSoftInputMode);

    // Hide keyboard when paused.
    View currentFocusView = activity.getCurrentFocus();

    if (currentFocusView != null) {
      InputMethodManager inputMethodManager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
      inputMethodManager.hideSoftInputFromWindow(activity.getCurrentFocus().getWindowToken(),
          InputMethodManager.RESULT_UNCHANGED_SHOWN);
    }
  }
}