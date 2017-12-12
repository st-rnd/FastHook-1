package com.ns.greg.library.fasthook.observer;

/**
 * Created by Gregory on 2016/5/20.
 */
public interface BaseObserver<T> {

  /**
   * When task completed
   *
   * @param data the result data
   */
  void onCompleted(T data);

  /**
   * When task interrupted
   *
   * @param data the result data
   */
  void onError(T data);
}
