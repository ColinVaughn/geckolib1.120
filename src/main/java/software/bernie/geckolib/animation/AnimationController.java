package software.bernie.geckolib.animation;

import net.minecraft.entity.Entity;
import org.antlr.v4.runtime.misc.NotNull;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.IAnimatedEntity;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.animation.keyframe.KeyFrame;
import software.bernie.geckolib.animation.keyframe.KeyFrameLocation;
import software.bernie.geckolib.animation.keyframe.VectorKeyFrameList;
import software.bernie.geckolib.model.*;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class AnimationController<T extends Entity & IAnimatedEntity>
{
	public T entity;
	public String name;
	// Keep track of what's going to happen
	AnimationState animationState = AnimationState.Stopped;

	public double transitionLength;
	public IAnimationPredicate animationPredicate;
	public double tickOffset = 0;
	public final HashMap<String, BoneAnimationQueue> boneAnimationQueues = new HashMap<>();
	private Queue<Animation> animationQueue = new LinkedList<>();
	private Animation currentAnimation;
	private AnimationBuilder currentAnimationBuilder = new AnimationBuilder();
	private boolean shouldResetTick = false;
	private HashMap<String, BoneSnapshot> boneSnapshots = new HashMap<>();
	private boolean justStopped = false;
	private boolean justStartedTransition = false;

	public interface IAnimationPredicate
	{
		<ENTITY extends Entity> boolean test(AnimationTestEvent<ENTITY> event);
	}

	/**
	 * Instantiates a new Animation controller. Each animation controller can run one animation at a time. You can have several animation controllers for each entity, i.e. one animation to control the entity's size, one to control movement, attacks, etc.
	 *
	 * @param entity             The entity
	 * @param name               Name of the animation controller (move_controller, size_controller, attack_controller, etc.)
	 * @param transitionLength   How long it takes to transition between animations (IN TICKS!!)
	 * @param animationPredicate The animation predicate that decides if the animation should stop, continue, or keep running. You should switch animations in this method most of the time.
	 */
	public AnimationController(T entity, String name, float transitionLength, IAnimationPredicate animationPredicate)
	{
		this.entity = entity;
		this.name = name;
		this.transitionLength = transitionLength;
		this.animationPredicate = animationPredicate;
	}


	public void setAnimation(@Nullable AnimationBuilder builder)
	{
		AnimatedEntityModel model = AnimationUtils.getModelForEntity(entity);
		if (model != null)
		{
			if (builder == null || builder.getRawAnimationList().size() == 0)
			{
				animationState = AnimationState.Stopped;
			}
			if (!builder.getRawAnimationList().equals(currentAnimationBuilder.getRawAnimationList()))
			{
				currentAnimationBuilder = builder;

				// Convert the list of animation names to the actual list, keeping track of the loop boolean along the way
				animationQueue = new LinkedList<>(builder.getRawAnimationList().stream().map((rawAnimation) ->
				{
					Animation animation = model.getAnimation(rawAnimation.animationName);
					if (rawAnimation.loop != null)
					{
						animation.loop = rawAnimation.loop;
					}
					return animation;
				}).collect(Collectors.toList()));

				// Reset the adjusted tick to 0 on next animation process call
				shouldResetTick = true;
				this.animationState = AnimationState.Transitioning;
				justStartedTransition = true;
			}
		}
	}


	public void process(double tick, AnimationTestEvent animationTestEvent, List<AnimatedModelRenderer> modelRendererList, BoneSnapshotCollection boneSnapshotCollection)
	{
		createInitialQueues(modelRendererList);

		double actualTick = tick;
		tick = adjustTick(tick);

		// Transition period has ended, reset the tick and set the animation to running
		if (animationState == AnimationState.Transitioning && tick >= transitionLength)
		{
			this.shouldResetTick = true;
			animationState = AnimationState.Running;
			tick = adjustTick(actualTick);
		}

		assert tick >= 0 : "GeckoLib: Tick was less than zero";

		// This tests the animation predicate
		boolean shouldStop = !this.animationPredicate.test(
				animationTestEvent);
		if (shouldStop || (currentAnimation == null && animationQueue.size() == 0))
		{
			// The animation should transition to the model's initial state
			animationState = AnimationState.Stopped;
			justStopped = true;
			return;
		}
		if (justStartedTransition && (shouldResetTick || justStopped))
		{
			justStopped = false;
			tick = adjustTick(actualTick);
			GeckoLib.LOGGER.info("reset tick");
		}
		else
		{
			if(animationState != AnimationState.Transitioning)
			{
				animationState = AnimationState.Running;
			}
		}
		// Handle transitioning to a different animation (or just starting one)
		if (animationState == AnimationState.Transitioning)
		{
			// Just started transitioning, so set the current animation to the first one
			if (tick == 0)
			{
				justStartedTransition = false;
				this.currentAnimation = animationQueue.poll();
				saveSnapshotsForAnimation(currentAnimation, boneSnapshotCollection);
			}
			for (BoneAnimation boneAnimation : currentAnimation.boneAnimations)
			{
				BoneAnimationQueue boneAnimationQueue = boneAnimationQueues.get(boneAnimation.boneName);
				BoneSnapshot boneSnapshot = this.boneSnapshots.get(boneAnimation.boneName);
				BoneSnapshot initialSnapshot = modelRendererList.stream().filter(x -> x.name.equals(boneAnimation.boneName)).findFirst().get().getInitialSnapshot();
				assert boneSnapshot != null : "Bone snapshot was null";

				VectorKeyFrameList<KeyFrame<Double>> rotationKeyFrames = boneAnimation.rotationKeyFrames;
				VectorKeyFrameList<KeyFrame<Double>> positionKeyFrames = boneAnimation.positionKeyFrames;
				VectorKeyFrameList<KeyFrame<Double>> scaleKeyFrames = boneAnimation.scaleKeyFrames;

				// Adding the initial positions of the upcoming animation, so the model transitions to the initial state of the new animation
				if (!rotationKeyFrames.xKeyFrames.isEmpty())
				{
					boneAnimationQueue.rotationXQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.rotationValueX - initialSnapshot.rotationValueX,
									rotationKeyFrames.xKeyFrames.get(0).getStartValue()));
					boneAnimationQueue.rotationYQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.rotationValueY - initialSnapshot.rotationValueY,
									rotationKeyFrames.yKeyFrames.get(0).getStartValue()));
					boneAnimationQueue.rotationZQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.rotationValueZ - initialSnapshot.rotationValueZ,
									rotationKeyFrames.zKeyFrames.get(0).getStartValue()));
				}

				if (!positionKeyFrames.xKeyFrames.isEmpty())
				{
					boneAnimationQueue.positionXQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.positionOffsetX,
									positionKeyFrames.xKeyFrames.get(0).getStartValue()));
					boneAnimationQueue.positionYQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.positionOffsetY,
									positionKeyFrames.yKeyFrames.get(0).getStartValue()));
					boneAnimationQueue.positionZQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.positionOffsetZ,
									positionKeyFrames.zKeyFrames.get(0).getStartValue()));
				}

				if (!scaleKeyFrames.xKeyFrames.isEmpty())
				{
					boneAnimationQueue.scaleXQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.scaleValueX,
									scaleKeyFrames.xKeyFrames.get(0).getStartValue()));
					boneAnimationQueue.scaleYQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.scaleValueY,
									scaleKeyFrames.yKeyFrames.get(0).getStartValue()));
					boneAnimationQueue.scaleZQueue.add(
							new AnimationPoint(tick, transitionLength, boneSnapshot.scaleValueZ,
									scaleKeyFrames.zKeyFrames.get(0).getStartValue()));
				}
			}
		}
		else if (animationState == AnimationState.Running)
		{
			processCurrentAnimation(tick, actualTick);
		}
	}

	private void saveSnapshotsForAnimation(@NotNull Animation animation, BoneSnapshotCollection boneSnapshotCollection)
	{
		for (BoneSnapshot snapshot : boneSnapshotCollection.values())
		{
			if (animation.boneAnimations.stream().anyMatch(x -> x.boneName.equals(snapshot.name)))
			{
				this.boneSnapshots.put(snapshot.name, new BoneSnapshot(snapshot));
			}
		}
	}

	private void processCurrentAnimation(double tick, double actualTick)
	{
		assert currentAnimation != null;
		// Animation has ended
		if (tick >= currentAnimation.animationLength)
		{
			// Reset the adjusted tick so the next animation starts at tick 0
			shouldResetTick = true;
			tick = adjustTick(actualTick);

			// If the current animation is set to loop, keep it as the current animation and just start over
			if (!currentAnimation.loop)
			{
				// Pull the next animation from the queue
				this.currentAnimation = animationQueue.poll();
				if (currentAnimation == null)
				{
					// No more animations left, stop the animation controller
					this.animationState = AnimationState.Stopped;
					return;
				}
			}
		}

		List<BoneAnimation> boneAnimations = currentAnimation.boneAnimations;
		for (BoneAnimation boneAnimation : boneAnimations)
		{
			BoneAnimationQueue boneAnimationQueue = boneAnimationQueues.get(boneAnimation.boneName);

			VectorKeyFrameList<KeyFrame<Double>> rotationKeyFrames = boneAnimation.rotationKeyFrames;
			VectorKeyFrameList<KeyFrame<Double>> positionKeyFrames = boneAnimation.positionKeyFrames;
			VectorKeyFrameList<KeyFrame<Double>> scaleKeyFrames = boneAnimation.scaleKeyFrames;

			if (!rotationKeyFrames.xKeyFrames.isEmpty())
			{
				boneAnimationQueue.rotationXQueue.add(getAnimationPointAtTick(rotationKeyFrames.xKeyFrames, tick));
				boneAnimationQueue.rotationYQueue.add(getAnimationPointAtTick(rotationKeyFrames.yKeyFrames, tick));
				boneAnimationQueue.rotationZQueue.add(getAnimationPointAtTick(rotationKeyFrames.zKeyFrames, tick));
			}

			if (!positionKeyFrames.xKeyFrames.isEmpty())
			{
				boneAnimationQueue.positionXQueue.add(getAnimationPointAtTick(positionKeyFrames.xKeyFrames, tick));
				boneAnimationQueue.positionYQueue.add(getAnimationPointAtTick(positionKeyFrames.yKeyFrames, tick));
				boneAnimationQueue.positionZQueue.add(getAnimationPointAtTick(positionKeyFrames.zKeyFrames, tick));
			}

			if (!scaleKeyFrames.xKeyFrames.isEmpty())
			{
				boneAnimationQueue.scaleXQueue.add(getAnimationPointAtTick(scaleKeyFrames.xKeyFrames, tick));
				boneAnimationQueue.scaleYQueue.add(getAnimationPointAtTick(scaleKeyFrames.yKeyFrames, tick));
				boneAnimationQueue.scaleZQueue.add(getAnimationPointAtTick(scaleKeyFrames.zKeyFrames, tick));
			}
		}
	}

	private void createInitialQueues(List<AnimatedModelRenderer> modelRendererList)
	{
		if (boneAnimationQueues.size() == 0)
		{
			for (AnimatedModelRenderer modelRenderer : modelRendererList)
			{
				boneAnimationQueues.put(modelRenderer.name, new BoneAnimationQueue(modelRenderer));
			}
		}
	}

	private double adjustTick(double tick)
	{
		if (shouldResetTick)
		{
			this.tickOffset = tick;
			shouldResetTick = false;
			return 0;
		}
		//assert tick - this.tickOffset >= 0;
		return (tick - this.tickOffset < 0 ? 0 : tick - this.tickOffset);
	}

	private AnimationPoint getAnimationPointAtTick(List<KeyFrame<Double>> frames, double tick)
	{
		KeyFrameLocation<KeyFrame<Double>> location = getCurrentKeyFrameLocation(frames, tick);
		KeyFrame<Double> currentFrame = location.CurrentFrame;
		return new AnimationPoint(location.CurrentAnimationTick, currentFrame.getLength(), currentFrame.getStartValue(),
				currentFrame.getEndValue());
	}

	/*
	Returns the current keyframe object, plus how long the previous keyframes have taken (aka elapsed animation time)
    */
	private static KeyFrameLocation<KeyFrame<Double>> getCurrentKeyFrameLocation(List<KeyFrame<Double>> frames, double ageInTicks)
	{
		double totalTimeTracker = 0;
		for (int i = 0; i < frames.size(); i++)
		{
			KeyFrame frame = frames.get(i);
			totalTimeTracker += frame.getLength();
			if (totalTimeTracker >= ageInTicks)
			{
				double tick = (ageInTicks - (totalTimeTracker - frame.getLength()));
				return new KeyFrameLocation<>(frame, tick);
			}
		}

		return new KeyFrameLocation(frames.get(frames.size() - 1), ageInTicks);
	}
}