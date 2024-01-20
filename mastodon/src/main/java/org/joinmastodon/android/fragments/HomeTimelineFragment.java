package org.joinmastodon.android.fragments;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.markers.SaveMarkers;
import org.joinmastodon.android.api.requests.timelines.GetHomeTimeline;
import org.joinmastodon.android.api.requests.timelines.GetListTimeline;
import org.joinmastodon.android.api.requests.timelines.GetPublicTimeline;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.SelfUpdateStateChangedEvent;
import org.joinmastodon.android.fragments.settings.SettingsMainFragment;
import org.joinmastodon.android.model.CacheablePaginatedResponse;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.FollowList;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.TimelineMarkers;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.displayitems.GapStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.utils.DiscoverInfoBannerHelper;
import org.joinmastodon.android.ui.utils.HideableSingleViewRecyclerAdapter;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.viewcontrollers.HomeTimelineMenuController;
import org.joinmastodon.android.ui.viewcontrollers.ToolbarDropdownMenuController;
import org.joinmastodon.android.ui.views.FixedAspectRatioImageView;
import org.joinmastodon.android.updater.GithubSelfUpdater;
import org.parceler.Parcels;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.api.SimpleCallback;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import me.grishka.appkit.utils.V;

public class HomeTimelineFragment extends StatusListFragment implements ToolbarDropdownMenuController.HostFragment{
	private ImageButton fab;
	private LinearLayout listsDropdown;
	private FixedAspectRatioImageView listsDropdownArrow;
	private TextView listsDropdownText;
	private Button newPostsBtn;
	private View newPostsBtnWrap;
	private boolean newPostsBtnShown;
	private AnimatorSet currentNewPostsAnim;
	private ToolbarDropdownMenuController dropdownController;
	private HomeTimelineMenuController dropdownMainMenuController;
	private List<FollowList> lists=List.of();
	private ListMode listMode=ListMode.FOLLOWING;
	private FollowList currentList;
	private MergeRecyclerAdapter mergeAdapter;
	private DiscoverInfoBannerHelper localTimelineBannerHelper;
	private HideableSingleViewRecyclerAdapter yearlyWrapBannerAdapter;

	private String maxID;
	private String lastSavedMarkerID;

	public HomeTimelineFragment(){
		setListLayoutId(R.layout.fragment_timeline);
	}

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		localTimelineBannerHelper=new DiscoverInfoBannerHelper(DiscoverInfoBannerHelper.BannerType.LOCAL_TIMELINE, accountID);
	}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		dropdownController=new ToolbarDropdownMenuController(this);
		dropdownMainMenuController=new HomeTimelineMenuController(dropdownController, new HomeTimelineMenuController.Callback(){
			@Override
			public void onFollowingSelected(){
				if(listMode==ListMode.FOLLOWING)
					return;
				listMode=ListMode.FOLLOWING;
				reload();
			}

			@Override
			public void onLocalSelected(){
				if(listMode==ListMode.LOCAL)
					return;
				listMode=ListMode.LOCAL;
				reload();
			}

			@Override
			public List<FollowList> getLists(){
				return lists;
			}

			@Override
			public void onListSelected(FollowList list){
				if(listMode==ListMode.LIST && currentList==list)
					return;
				listMode=ListMode.LIST;
				currentList=list;
				reload();
			}
		});
		setHasOptionsMenu(true);
		loadData();
		AccountSessionManager.get(accountID).getCacheController().getLists(new Callback<>(){
			@Override
			public void onSuccess(List<FollowList> result){
				lists=result;
			}

			@Override
			public void onError(ErrorResponse error){}
		});
	}

	@Override
	protected void doLoadData(int offset, int count){
		switch(listMode){
			case FOLLOWING -> {
				AccountSessionManager.getInstance()
						.getAccount(accountID).getCacheController()
						.getHomeTimeline(offset>0 ? maxID : null, count, refreshing, new SimpleCallback<>(this){
							@Override
							public void onSuccess(CacheablePaginatedResponse<List<Status>> result){
								if(getActivity()==null || listMode!=ListMode.FOLLOWING)
									return;
								if(refreshing)
									list.scrollToPosition(0);
								onDataLoaded(result.items, !result.items.isEmpty());
								maxID=result.maxID;
								if(result.isFromCache())
									loadNewPosts();
							}

							@Override
							public void onError(ErrorResponse error){
								if(listMode!=ListMode.FOLLOWING)
									return;
								super.onError(error);
							}
						});
			}
			case LOCAL -> {
				currentRequest=new GetPublicTimeline(true, false, offset>0 ? maxID : null, null, count, null)
						.setCallback(new SimpleCallback<>(this){
							@Override
							public void onSuccess(List<Status> result){
								if(refreshing)
									list.scrollToPosition(0);
								maxID=result.isEmpty() ? null : result.get(result.size()-1).id;
								AccountSessionManager.get(accountID).filterStatuses(result, FilterContext.PUBLIC);
								onDataLoaded(result, !result.isEmpty());
							}
						})
						.exec(accountID);
			}
			case LIST -> {
				currentRequest=new GetListTimeline(currentList.id, offset>0 ? maxID : null, null, count, null)
						.setCallback(new SimpleCallback<>(this){
							@Override
							public void onSuccess(List<Status> result){
								if(refreshing)
									list.scrollToPosition(0);
								maxID=result.isEmpty() ? null : result.get(result.size()-1).id;
								AccountSessionManager.get(accountID).filterStatuses(result, FilterContext.HOME);
								onDataLoaded(result, !result.isEmpty());
							}
						})
						.exec(accountID);
			}
		}
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		fab=view.findViewById(R.id.fab);
		fab.setOnClickListener(this::onFabClick);
		newPostsBtn=view.findViewById(R.id.new_posts_btn);
		newPostsBtn.setOnClickListener(this::onNewPostsBtnClick);
		newPostsBtnWrap=view.findViewById(R.id.new_posts_btn_wrap);

		if(newPostsBtnShown){
			newPostsBtnWrap.setVisibility(View.VISIBLE);
		}else{
			newPostsBtnWrap.setVisibility(View.GONE);
			newPostsBtnWrap.setScaleX(0.9f);
			newPostsBtnWrap.setScaleY(0.9f);
			newPostsBtnWrap.setAlpha(0f);
			newPostsBtnWrap.setTranslationY(V.dp(-56));
		}
		updateToolbarLogo();
		list.addOnScrollListener(new RecyclerView.OnScrollListener(){
			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy){
				if(newPostsBtnShown && list.getChildAdapterPosition(list.getChildAt(0))<=getMainAdapterOffset()){
					hideNewPostsButton();
				}
			}
		});

		if(GithubSelfUpdater.needSelfUpdating()){
			E.register(this);
			updateUpdateState(GithubSelfUpdater.getInstance().getState());
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
		inflater.inflate(R.menu.home, menu);
		menu.findItem(R.id.edit_list).setVisible(listMode==ListMode.LIST);
		GithubSelfUpdater.UpdateState state=GithubSelfUpdater.UpdateState.NO_UPDATE;
		GithubSelfUpdater updater=GithubSelfUpdater.getInstance();
		if(updater!=null)
			state=updater.getState();
		if(state!=GithubSelfUpdater.UpdateState.NO_UPDATE && state!=GithubSelfUpdater.UpdateState.CHECKING)
			getToolbar().getMenu().findItem(R.id.settings).setIcon(R.drawable.ic_settings_updateready_24px);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		int id=item.getItemId();
		if(id==R.id.settings){
			Nav.go(getActivity(), SettingsMainFragment.class, args);
		}else if(id==R.id.edit_list){
			args.putParcelable("list", Parcels.wrap(currentList));
			Nav.go(getActivity(), EditListFragment.class, args);
		}
		return true;
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){
		super.onConfigurationChanged(newConfig);
		updateToolbarLogo();
	}

	@Override
	protected void onShown(){
		super.onShown();
		if(!getArguments().getBoolean("noAutoLoad")){
			if(!loaded && !dataLoading){
				loadData();
			}else if(!dataLoading){
				loadNewPosts();
			}
		}
	}

	@Override
	protected void onHidden(){
		super.onHidden();
		if(!data.isEmpty() && listMode==ListMode.FOLLOWING){
			String topPostID=displayItems.get(Math.max(0, list.getChildAdapterPosition(list.getChildAt(0))-getMainAdapterOffset())).parentID;
			if(!topPostID.equals(lastSavedMarkerID)){
				lastSavedMarkerID=topPostID;
				new SaveMarkers(topPostID, null)
						.setCallback(new Callback<>(){
							@Override
							public void onSuccess(TimelineMarkers result){
							}

							@Override
							public void onError(ErrorResponse error){
								lastSavedMarkerID=null;
							}
						})
						.exec(accountID);
			}
		}
	}

	public void onStatusCreated(Status status){
		prependItems(Collections.singletonList(status), true);
	}

	private void onFabClick(View v){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), ComposeFragment.class, args);
	}

	private void loadNewPosts(){
		dataLoading=true;
		// The idea here is that we request the timeline such that if there are fewer than `limit` posts,
		// we'll get the currently topmost post as last in the response. This way we know there's no gap
		// between the existing and newly loaded parts of the timeline.
		String sinceID=data.size()>1 ? data.get(1).id : "1";
		boolean needCache=listMode==ListMode.FOLLOWING;
		loadAdditionalPosts(null, null, 20, sinceID, new Callback<>(){
					@Override
					public void onSuccess(List<Status> result){
						currentRequest=null;
						dataLoading=false;
						if(result.isEmpty() || getActivity()==null)
							return;
						Status last=result.get(result.size()-1);
						List<Status> toAdd;
						if(!data.isEmpty() && last.id.equals(data.get(0).id)){ // This part intersects with the existing one
							toAdd=result.subList(0, result.size()-1); // Remove the already known last post
						}else{
							result.get(result.size()-1).hasGapAfter=true;
							toAdd=result;
						}
						if(needCache)
							AccountSessionManager.get(accountID).filterStatuses(toAdd, FilterContext.HOME);
						if(!toAdd.isEmpty()){
							prependItems(toAdd, true);
							showNewPostsButton();
							if(needCache)
								AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(toAdd, false);
						}
					}

					@Override
					public void onError(ErrorResponse error){
						currentRequest=null;
						dataLoading=false;
					}
				});
	}

	@Override
	public void onGapClick(GapStatusDisplayItem.Holder item){
		if(dataLoading)
			return;
		item.getItem().loading=true;
		V.setVisibilityAnimated(item.progress, View.VISIBLE);
		V.setVisibilityAnimated(item.text, View.GONE);
		GapStatusDisplayItem gap=item.getItem();
		dataLoading=true;
		boolean needCache=listMode==ListMode.FOLLOWING;
		loadAdditionalPosts(item.getItemID(), null, 20, null, new Callback<>(){
					@Override
					public void onSuccess(List<Status> result){

						currentRequest=null;
						dataLoading=false;
						if(getActivity()==null)
							return;
						int gapPos=displayItems.indexOf(gap);
						if(gapPos==-1)
							return;
						if(result.isEmpty()){
							displayItems.remove(gapPos);
							adapter.notifyItemRemoved(getMainAdapterOffset()+gapPos);
							Status gapStatus=getStatusByID(gap.parentID);
							if(gapStatus!=null){
								gapStatus.hasGapAfter=false;
								if(needCache)
									AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(Collections.singletonList(gapStatus), false);
							}
						}else{
							Set<String> idsBelowGap=new HashSet<>();
							boolean belowGap=false;
							int gapPostIndex=0;
							for(Status s:data){
								if(belowGap){
									idsBelowGap.add(s.id);
								}else if(s.id.equals(gap.parentID)){
									belowGap=true;
									s.hasGapAfter=false;
									if(needCache)
										AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(Collections.singletonList(s), false);
								}else{
									gapPostIndex++;
								}
							}
							int endIndex=0;
							for(Status s:result){
								endIndex++;
								if(idsBelowGap.contains(s.id))
									break;
							}
							if(endIndex==result.size()){
								result.get(result.size()-1).hasGapAfter=true;
							}else{
								result=result.subList(0, endIndex);
							}
							if(needCache)
								AccountSessionManager.get(accountID).filterStatuses(result, FilterContext.HOME);
							List<StatusDisplayItem> targetList=displayItems.subList(gapPos, gapPos+1);
							targetList.clear();
							List<Status> insertedPosts=data.subList(gapPostIndex+1, gapPostIndex+1);
							for(Status s:result){
								if(idsBelowGap.contains(s.id))
									break;
								targetList.addAll(buildDisplayItems(s));
								insertedPosts.add(s);
							}
							if(targetList.isEmpty()){
								// oops. We didn't add new posts, but at least we know there are none.
								adapter.notifyItemRemoved(getMainAdapterOffset()+gapPos);
							}else{
								adapter.notifyItemChanged(getMainAdapterOffset()+gapPos);
								adapter.notifyItemRangeInserted(getMainAdapterOffset()+gapPos+1, targetList.size()-1);
							}
							if(needCache)
								AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(insertedPosts, false);
						}
					}

					@Override
					public void onError(ErrorResponse error){
						currentRequest=null;
						dataLoading=false;
						gap.loading=false;
						Activity a=getActivity();
						if(a!=null){
							error.showToast(a);
							int gapPos=displayItems.indexOf(gap);
							if(gapPos>=0)
								adapter.notifyItemChanged(gapPos);
						}
					}
				});
	}

	private void loadAdditionalPosts(String maxID, String minID, int limit, String sinceID, Callback<List<Status>> callback){
		MastodonAPIRequest<List<Status>> req=switch(listMode){
			case FOLLOWING -> new GetHomeTimeline(maxID, minID, limit, sinceID);
			case LOCAL -> new GetPublicTimeline(true, false, maxID, minID, limit, sinceID);
			case LIST -> new GetListTimeline(currentList.id, maxID, minID, limit, sinceID);
		};
		currentRequest=req;
		req.setCallback(callback).exec(accountID);
	}

	@Override
	public void onRefresh(){
		if(currentRequest!=null){
			currentRequest.cancel();
			currentRequest=null;
			dataLoading=false;
		}
		super.onRefresh();
	}

	private void updateToolbarLogo(){
		listsDropdown=new LinearLayout(getActivity());
		listsDropdown.setOnClickListener(this::onListsDropdownClick);
		listsDropdown.setBackgroundResource(R.drawable.bg_button_m3_text);
		listsDropdown.setAccessibilityDelegate(new View.AccessibilityDelegate(){
			@Override
			public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName("android.widget.Spinner");
			}
		});
		listsDropdownArrow=new FixedAspectRatioImageView(getActivity());
		listsDropdownArrow.setUseHeight(true);
		listsDropdownArrow.setImageResource(R.drawable.ic_arrow_drop_down_24px);
		listsDropdownArrow.setScaleType(ImageView.ScaleType.CENTER);
		listsDropdownArrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		listsDropdown.addView(listsDropdownArrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
		listsDropdownText=new TextView(getActivity());
		listsDropdownText.setTextAppearance(R.style.action_bar_title);
		listsDropdownText.setSingleLine();
		listsDropdownText.setEllipsize(TextUtils.TruncateAt.END);
		listsDropdownText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
		listsDropdownText.setPaddingRelative(V.dp(4), 0, V.dp(16), 0);
		listsDropdownText.setText(getCurrentListTitle());
		listsDropdownArrow.setImageTintList(listsDropdownText.getTextColors());
		listsDropdown.setBackgroundTintList(listsDropdownText.getTextColors());
		listsDropdown.addView(listsDropdownText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

		FrameLayout logoWrap=new FrameLayout(getActivity());
		FrameLayout.LayoutParams ddlp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
		ddlp.topMargin=ddlp.bottomMargin=V.dp(8);
		logoWrap.addView(listsDropdown, ddlp);

		Toolbar toolbar=getToolbar();
		toolbar.addView(logoWrap, new Toolbar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		toolbar.setContentInsetsRelative(V.dp(16), 0);
	}

	private void showNewPostsButton(){
		if(newPostsBtnShown)
			return;
		newPostsBtnShown=true;
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		newPostsBtnWrap.setVisibility(View.VISIBLE);
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.ALPHA, 1f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_X, 1f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_Y, 1f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.TRANSLATION_Y, 0f)
		);
		set.setDuration(getResources().getInteger(R.integer.m3_sys_motion_duration_medium3));
		set.setInterpolator(AnimationUtils.loadInterpolator(getActivity(), R.interpolator.m3_sys_motion_easing_standard_decelerate));
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	private void hideNewPostsButton(){
		if(!newPostsBtnShown)
			return;
		newPostsBtnShown=false;
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.ALPHA, 0f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_X, .9f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_Y, .9f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.TRANSLATION_Y, V.dp(-56))
		);
		set.setDuration(getResources().getInteger(R.integer.m3_sys_motion_duration_medium3));
		set.setInterpolator(AnimationUtils.loadInterpolator(getActivity(), R.interpolator.m3_sys_motion_easing_standard_accelerate));
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				newPostsBtnWrap.setVisibility(View.GONE);
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	private void onNewPostsBtnClick(View v){
		if(newPostsBtnShown){
			hideNewPostsButton();
			scrollToTop();
		}
	}

	private void onListsDropdownClick(View v){
		listsDropdownArrow.animate().rotation(-180f).setDuration(150).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
		dropdownController.show(dropdownMainMenuController);
		AccountSessionManager.get(accountID).getCacheController().reloadLists(new Callback<>(){
			@Override
			public void onSuccess(java.util.List<FollowList> result){
				lists=result;
			}

			@Override
			public void onError(ErrorResponse error){}
		});
	}

	@Override
	public void onDestroyView(){
		super.onDestroyView();
		if(GithubSelfUpdater.needSelfUpdating()){
			E.unregister(this);
		}
	}

	private void updateUpdateState(GithubSelfUpdater.UpdateState state){
		if(state!=GithubSelfUpdater.UpdateState.NO_UPDATE && state!=GithubSelfUpdater.UpdateState.CHECKING)
			getToolbar().getMenu().findItem(R.id.settings).setIcon(R.drawable.ic_settings_updateready_24px);
	}

	@Subscribe
	public void onSelfUpdateStateChanged(SelfUpdateStateChangedEvent ev){
		updateUpdateState(ev.state);
	}

	@Override
	protected boolean shouldRemoveAccountPostsWhenUnfollowing(){
		return true;
	}

	@Override
	public Toolbar getToolbar(){
		return super.getToolbar();
	}

	@Override
	public void onDropdownWillDismiss(){
		listsDropdownArrow.animate().rotation(0f).setDuration(150).setInterpolator(CubicBezierInterpolator.DEFAULT).start();

	}

	@Override
	public void onDropdownDismissed(){

	}

	@Override
	public void reload(){
		if(currentRequest!=null){
			currentRequest.cancel();
			currentRequest=null;
		}
		refreshing=true;
		showProgress();
		loadData();
		listsDropdownText.setText(getCurrentListTitle());
		invalidateOptionsMenu();
	}

	@Override
	protected RecyclerView.Adapter getAdapter(){
		yearlyWrapBannerAdapter=new HideableSingleViewRecyclerAdapter(makeWrapstodonBannerView());
		// TODO only show banner when some condition is met (TBD)

		mergeAdapter=new MergeRecyclerAdapter();
		mergeAdapter.addAdapter(yearlyWrapBannerAdapter);
		mergeAdapter.addAdapter(super.getAdapter());
		return mergeAdapter;
	}

	@Override
	protected void onDataLoaded(List<Status> d, boolean more){
		if(refreshing){
			if(listMode==ListMode.LOCAL){
				localTimelineBannerHelper.maybeAddBanner(list, mergeAdapter);
				localTimelineBannerHelper.onBannerBecameVisible();
			}else{
				localTimelineBannerHelper.removeBanner(mergeAdapter);
			}
		}
		super.onDataLoaded(d, more);
	}

	private String getCurrentListTitle(){
		return switch(listMode){
			case FOLLOWING -> getString(R.string.timeline_following);
			case LOCAL -> getString(R.string.local_timeline);
			case LIST -> currentList.title;
		};
	}

	private View makeWrapstodonBannerView(){
		int bgColor, accentColor, iconContainerColor, textColor;
		accentColor=0xFFBAFF3B;
		bgColor=0xFF2F0C7A;
		iconContainerColor=0xFF563ACC;
		if(UiUtils.isDarkTheme()){
			textColor=0xFFEEE5FF;
		}else{
			textColor=0xFFCCCFFF;
		}
		View banner=getActivity().getLayoutInflater().inflate(R.layout.item_settings_banner, list, false);
		TextView bannerText=banner.findViewById(R.id.text);
		TextView bannerTitle=banner.findViewById(R.id.title);
		ImageView bannerIcon=banner.findViewById(R.id.icon);
		Button bannerButton=banner.findViewById(R.id.button);
		banner.findViewById(R.id.button2).setVisibility(View.GONE);
		bannerTitle.setText(getString(R.string.yearly_wrap_title, "2023"));
		bannerText.setText(R.string.yearly_wrap_text);
		bannerButton.setText(R.string.yearly_wrap_view);
		bannerIcon.setImageResource(R.drawable.ic_celebration_24px);
		banner.setBackgroundColor(bgColor);
		bannerText.setTextColor(textColor);
		bannerTitle.setTextColor(textColor);
		bannerIcon.setBackgroundTintList(ColorStateList.valueOf(iconContainerColor));
		bannerIcon.setImageTintList(ColorStateList.valueOf(accentColor));
		bannerButton.setTextColor(accentColor);
		bannerButton.setBackgroundTintList(ColorStateList.valueOf(accentColor));
		bannerButton.setOnClickListener(v->{
			Bundle args=new Bundle();
			args.putString("account", accountID);
			Nav.go(getActivity(), WrapstodonFragment.class, args);
		});
		banner.setOutlineProvider(OutlineProviders.roundedRect(12));
		banner.setClipToOutline(true);
		return banner;
	}

	private enum ListMode{
		FOLLOWING,
		LOCAL,
		LIST
	}
}
